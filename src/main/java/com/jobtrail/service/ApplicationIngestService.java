package com.jobtrail.service;

import com.jobtrail.domain.ApplicationEvent;
import com.jobtrail.domain.ApplicationEventKind;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.domain.JobPlatform;
import com.jobtrail.repo.ApplicationEventRepository;
import com.jobtrail.repo.JobApplicationRepository;
import com.jobtrail.service.scan.Classification;
import com.jobtrail.service.scan.ScannedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a classified message into stored state: finds or creates the
 * application it belongs to, records the event, and raises an alert when the
 * event deserves one.
 *
 * <p>Each message is ingested in its own transaction so one malformed mail
 * cannot roll back a whole 30-day scan.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationIngestService {

    /**
     * Kinds that prove you actually applied. Anything outside this set attaches
     * to an application only if one already exists — an unsolicited recruiter
     * mail must never invent an application, or every "applied" figure on the
     * dashboard becomes fiction.
     */
    private static final Set<ApplicationEventKind> CREATES_APPLICATION = EnumSet.of(
            ApplicationEventKind.APPLIED,
            ApplicationEventKind.ACKNOWLEDGED,
            ApplicationEventKind.ASSESSMENT_INVITE,
            ApplicationEventKind.INTERVIEW_INVITE,
            ApplicationEventKind.OFFER,
            ApplicationEventKind.REJECTED,
            ApplicationEventKind.STATUS_UPDATE);

    private final JobApplicationRepository applicationRepo;
    private final ApplicationEventRepository eventRepo;
    private final AlertService alertService;

    /** What one message did, so the scanner can report a useful summary. */
    public record Outcome(boolean stored, boolean newApplication, boolean alerted) {
        static final Outcome SKIPPED = new Outcome(false, false, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome ingest(ScannedMessage message, Classification classification) {
        if (eventRepo.existsByMessageId(message.messageId())) {
            return Outcome.SKIPPED;
        }

        Optional<JobApplication> resolved = resolve(classification);
        if (resolved.isEmpty() && !CREATES_APPLICATION.contains(classification.kind())) {
            return Outcome.SKIPPED;
        }

        boolean isNew = resolved.isEmpty();
        JobApplication application = resolved.orElseGet(() -> create(message, classification));

        enrich(application, classification);

        ApplicationEvent event = new ApplicationEvent();
        event.setApplication(application);
        event.setKind(classification.kind());
        event.setMessageId(message.messageId());
        event.setSubject(cap(message.subject(), 500));
        event.setFromAddress(cap(message.fromAddress(), 254));
        event.setFromName(cap(message.fromName(), 200));
        event.setReceivedAt(message.receivedAt());
        event.setSnippet(cap(message.body().strip(), 1000));
        event.setActionUrl(cap(classification.actionUrl(), 1000));
        event.setDeadlineAt(classification.deadlineAt());
        event.setConfidence(classification.confidence());

        application.apply(event);

        try {
            applicationRepo.save(application);
            eventRepo.save(event);
        } catch (DataIntegrityViolationException e) {
            // Another pass stored this message between our check and our write.
            log.debug("Message {} was already ingested", message.messageId());
            return Outcome.SKIPPED;
        }

        boolean alerted = alertService.raiseForEvent(application, event).isPresent();
        return new Outcome(true, isNew, alerted);
    }

    /**
     * Finds the application this mail belongs to. Mail about a job rarely
     * repeats every identifying detail — an assessment invite often names the
     * company but not the role — so an exact key match is tried first and then
     * progressively looser ones, rather than creating a near-duplicate row.
     */
    private Optional<JobApplication> resolve(Classification c) {
        JobPlatform platform = c.platform();
        String company = c.company();
        String role = c.roleTitle();

        String exact = JobApplication.dedupeKey(platform, company, role);
        Optional<JobApplication> hit = applicationRepo.findByDedupeKey(exact);
        if (hit.isPresent()) {
            return hit;
        }

        String companyNorm = JobApplication.normalise(company);
        if (companyNorm.isEmpty() || "unknown".equals(companyNorm)) {
            return Optional.empty();
        }

        // Same platform and company: attach to the most recent one. When this
        // mail names a role and the stored row does not, fill the gap.
        List<JobApplication> sameCompany = applicationRepo
                .findByDedupeKeyStartingWithOrderByAppliedAtDesc(platform.name() + '|' + companyNorm + '|');
        Optional<JobApplication> match = pickBestRoleMatch(sameCompany, role);
        if (match.isPresent()) {
            return match;
        }

        // The platform could not be identified, but the company and role can
        // still tie this to a row created from a mail that did identify one.
        if (platform == JobPlatform.UNKNOWN || platform == JobPlatform.DIRECT) {
            List<JobApplication> anyPlatform = applicationRepo
                    .findByDedupeKeyEndingWith('|' + companyNorm + '|' + JobApplication.normalise(role));
            if (!anyPlatform.isEmpty()) {
                return Optional.of(anyPlatform.get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * Prefers a row whose role matches; failing that, a row with no role yet
     * (which this mail can complete). A row with a *different* known role is a
     * genuinely different application and is left alone.
     */
    private Optional<JobApplication> pickBestRoleMatch(List<JobApplication> candidates, String role) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String roleNorm = JobApplication.normalise(role);
        if (roleNorm.isEmpty()) {
            return Optional.of(candidates.get(0));
        }
        for (JobApplication a : candidates) {
            if (roleNorm.equals(JobApplication.normalise(a.getRoleTitle()))) {
                return Optional.of(a);
            }
        }
        return candidates.stream()
                .filter(a -> a.getRoleTitle() == null || a.getRoleTitle().isBlank())
                .findFirst();
    }

    private JobApplication create(ScannedMessage message, Classification c) {
        JobApplication application = new JobApplication();
        application.setCompany(cap(c.company(), 200));
        application.setRoleTitle(cap(c.roleTitle(), 250));
        application.setLocation(cap(c.location(), 160));
        application.setPlatform(c.platform());
        application.setSourceEmail(cap(message.fromAddress(), 254));
        application.setJobUrl(cap(c.jobUrl(), 1000));
        application.setAppliedAt(message.receivedAt());
        application.setDedupeKey(JobApplication.dedupeKey(c.platform(), c.company(), c.roleTitle()));
        return application;
    }

    /**
     * Fills in details a later mail revealed. Learning the role changes the
     * identity of the row, so the key is only rewritten when nothing else has
     * claimed it.
     */
    private void enrich(JobApplication application, Classification c) {
        if (blank(application.getRoleTitle()) && !blank(c.roleTitle())) {
            application.setRoleTitle(cap(c.roleTitle(), 250));
            String rekeyed = JobApplication.dedupeKey(
                    application.getPlatform(), application.getCompany(), c.roleTitle());
            if (applicationRepo.findByDedupeKey(rekeyed).isEmpty()) {
                application.setDedupeKey(rekeyed);
            }
        }
        if (blank(application.getLocation()) && !blank(c.location())) {
            application.setLocation(cap(c.location(), 160));
        }
        if (blank(application.getJobUrl()) && !blank(c.jobUrl())) {
            application.setJobUrl(cap(c.jobUrl(), 1000));
        }
        // A vague first mail can leave the platform unknown; a later one that
        // identifies it is an upgrade worth taking.
        if (application.getPlatform() == JobPlatform.UNKNOWN
                && c.platform() != JobPlatform.UNKNOWN) {
            application.setPlatform(c.platform());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
