package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.domain.EmailMessage;
import com.jobtrail.domain.EmailTemplate;
import com.jobtrail.domain.MessageKind;
import com.jobtrail.domain.MessageStatus;
import com.jobtrail.domain.Outreach;
import com.jobtrail.domain.OutreachStatus;
import com.jobtrail.domain.TemplateKind;
import com.jobtrail.repo.EmailMessageRepository;
import com.jobtrail.repo.EmailTemplateRepository;
import com.jobtrail.repo.OutreachRepository;
import com.jobtrail.web.ApiException;
import com.jobtrail.web.dto.BulkOutreachForm;
import com.jobtrail.web.dto.OutreachForm;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Everything that creates, advances or closes an outreach thread. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutreachService {

    private final OutreachRepository outreachRepo;
    private final EmailMessageRepository messageRepo;
    private final EmailTemplateRepository templateRepo;
    private final SettingsService settingsService;
    private final TemplateRenderer renderer;

    // ---- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Views.OutreachView> list(String query, String status) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        OutreachStatus wanted = parseStatus(status);
        return outreachRepo.findAllByOrderByUpdatedAtDesc().stream()
                .filter(o -> wanted == null || o.getStatus() == wanted)
                .filter(o -> q.isEmpty() || matches(o, q))
                .map(Views::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public Views.OutreachDetail detail(Long id) {
        Outreach o = require(id);
        List<Views.MessageView> messages = messageRepo.findByOutreachIdOrderBySequenceNoAscIdAsc(id)
                .stream().map(Views::of).toList();
        return new Views.OutreachDetail(Views.of(o), messages);
    }

    // ---- writes ------------------------------------------------------------

    @Transactional
    public Views.OutreachView create(OutreachForm f) {
        AppSettings s = settingsService.get();
        Outreach o = new Outreach();
        applyForm(o, f, s, true);
        o = outreachRepo.save(o);

        if (Boolean.TRUE.equals(f.getQueueNow())) {
            queueInitialFor(o, f.getInitialTemplateId(), f.getSubjectOverride(), f.getBodyOverride(),
                    f.getScheduledAt(), s);
        }
        return Views.of(o);
    }

    /** Adds many recipients at once, skipping addresses that already have a live thread. */
    @Transactional
    public BulkResult createBulk(BulkOutreachForm form) {
        AppSettings s = settingsService.get();
        List<Views.OutreachView> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();

        for (BulkOutreachForm.Recipient r : form.getRecipients()) {
            String email = normaliseEmail(r.getEmail());
            if (email.isEmpty() || !seenInBatch.add(email)) {
                skipped.add(r.getEmail() + " (duplicate in list)");
                continue;
            }
            if (hasLiveThread(email)) {
                skipped.add(email + " (already has an active thread)");
                continue;
            }

            OutreachForm f = new OutreachForm();
            f.setRecipientName(r.getName());
            f.setRecipientEmail(email);
            f.setCompany(r.getCompany());
            f.setPosition(r.getPosition());
            f.setInitialTemplateId(form.getInitialTemplateId());
            f.setFollowUpTemplateId(form.getFollowUpTemplateId());
            f.setFollowUpIntervalDays(form.getFollowUpIntervalDays());
            f.setMaxFollowUps(form.getMaxFollowUps());
            f.setAutoFollowUp(form.getAutoFollowUp());

            Outreach o = new Outreach();
            applyForm(o, f, s, true);
            o = outreachRepo.save(o);
            if (Boolean.TRUE.equals(form.getQueueNow())) {
                queueInitialFor(o, form.getInitialTemplateId(), null, null, null, s);
            }
            created.add(Views.of(o));
        }
        return new BulkResult(created, skipped);
    }

    public record BulkResult(List<Views.OutreachView> created, List<String> skipped) {
    }

    @Transactional
    public Views.OutreachView update(Long id, OutreachForm f) {
        Outreach o = require(id);
        applyForm(o, f, settingsService.get(), false);
        return Views.of(outreachRepo.save(o));
    }

    @Transactional
    public void delete(Long id) {
        Outreach o = require(id);
        messageRepo.deleteAll(messageRepo.findByOutreachIdOrderBySequenceNoAscIdAsc(o.getId()));
        outreachRepo.delete(o);
    }

    /** Hands the opening email to the send queue. */
    @Transactional
    public Views.MessageView queueInitial(Long id, Long templateId, String subjectOverride,
                                          String bodyOverride, Instant scheduledAt) {
        Outreach o = require(id);
        AppSettings s = settingsService.get();
        if (hasPendingMessage(o.getId())) {
            throw ApiException.conflict("An email for this thread is already waiting in the queue.");
        }
        if (o.getFirstSentAt() != null) {
            throw ApiException.conflict(
                    "The opening email already went out. Use \"Send follow-up\" instead.");
        }
        return Views.of(queueInitialFor(o, templateId, subjectOverride, bodyOverride, scheduledAt, s));
    }

    /** Queues the next follow-up, threaded onto the original email. */
    @Transactional
    public Views.MessageView queueFollowUp(Long id, Long templateId, boolean ignoreCap) {
        Outreach o = require(id);
        AppSettings s = settingsService.get();

        if (o.getStatus() == OutreachStatus.REPLIED) {
            throw ApiException.conflict("They already replied — no follow-up needed.");
        }
        if (o.getStatus() == OutreachStatus.CLOSED) {
            throw ApiException.conflict("This thread is closed.");
        }
        if (o.getFirstSentAt() == null) {
            throw ApiException.conflict("Send the opening email before following up.");
        }
        if (hasPendingMessage(o.getId())) {
            throw ApiException.conflict("An email for this thread is already waiting in the queue.");
        }
        if (!ignoreCap && o.getFollowUpsSent() >= o.getMaxFollowUps()) {
            throw ApiException.conflict("This thread already used all "
                    + o.getMaxFollowUps() + " of its follow-ups.");
        }

        EmailTemplate t = resolveTemplate(
                templateId != null ? templateId : o.getFollowUpTemplateId(), TemplateKind.FOLLOW_UP);
        Map<String, String> vars = renderer.variables(o, s);

        EmailMessage m = new EmailMessage();
        m.setOutreach(o);
        m.setKind(MessageKind.FOLLOW_UP);
        m.setSequenceNo(o.getFollowUpsSent() + 1);
        m.setToEmail(o.getRecipientEmail());
        m.setSubject(followUpSubject(o, t, vars));
        m.setBodyHtml(renderer.render(t.getBodyHtml(), vars));
        m.setTrackingToken(newToken());
        m.setScheduledAt(Instant.now());
        m.setQueuedAt(Instant.now());
        m.setStatus(MessageStatus.QUEUED);
        messageRepo.save(m);

        o.setStatus(OutreachStatus.QUEUED);
        outreachRepo.save(o);
        return Views.of(m);
    }

    @Transactional
    public Views.PreviewView preview(Long id, Long templateId, String kind,
                                     String subjectOverride, String bodyOverride) {
        Outreach o = require(id);
        AppSettings s = settingsService.get();
        TemplateKind tk = "FOLLOW_UP".equalsIgnoreCase(kind) ? TemplateKind.FOLLOW_UP : TemplateKind.INITIAL;
        Map<String, String> vars = renderer.variables(o, s);

        String subject;
        String body;
        if (bodyOverride != null && !bodyOverride.isBlank()) {
            subject = renderer.render(subjectOverride == null ? "" : subjectOverride, vars);
            body = renderer.render(bodyOverride, vars);
        } else {
            EmailTemplate t = resolveTemplate(templateId != null ? templateId
                    : (tk == TemplateKind.FOLLOW_UP ? o.getFollowUpTemplateId() : o.getInitialTemplateId()), tk);
            subject = tk == TemplateKind.FOLLOW_UP
                    ? followUpSubject(o, t, vars)
                    : renderer.render(t.getSubject(), vars);
            body = renderer.render(t.getBodyHtml(), vars);
        }
        String html = renderer.wrapHtml(body, renderer.render(s.getSignatureHtml(), vars), null);
        return new Views.PreviewView(subject, html, renderer.toPlainText(html));
    }

    @Transactional
    public Views.OutreachView markReplied(Long id) {
        Outreach o = require(id);
        applyReply(o, Instant.now());
        return Views.of(outreachRepo.save(o));
    }

    @Transactional
    public Views.OutreachView setStatus(Long id, String status) {
        Outreach o = require(id);
        OutreachStatus target = parseStatus(status);
        if (target == null) {
            throw ApiException.badRequest("Unknown status: " + status);
        }
        if (target == OutreachStatus.REPLIED) {
            applyReply(o, Instant.now());
        } else {
            o.setStatus(target);
            if (target == OutreachStatus.CLOSED) {
                o.setNextFollowUpAt(null);
                cancelPending(o.getId());
            }
        }
        return Views.of(outreachRepo.save(o));
    }

    /** Marks a thread answered: stops the follow-up clock and drops queued mail. */
    void applyReply(Outreach o, Instant when) {
        o.setStatus(OutreachStatus.REPLIED);
        o.setRepliedAt(when);
        o.setNextFollowUpAt(null);
        cancelPending(o.getId());
    }

    private void cancelPending(Long outreachId) {
        List<EmailMessage> pending = messageRepo.findByOutreachIdAndStatus(outreachId, MessageStatus.QUEUED);
        for (EmailMessage m : pending) {
            m.setStatus(MessageStatus.CANCELLED);
        }
        messageRepo.saveAll(pending);
    }

    @Transactional
    public void cancelMessage(Long messageId) {
        EmailMessage m = messageRepo.findById(messageId)
                .orElseThrow(() -> ApiException.notFound("Message " + messageId + " not found"));
        if (m.getStatus() != MessageStatus.QUEUED) {
            throw ApiException.conflict("Only queued emails can be cancelled.");
        }
        m.setStatus(MessageStatus.CANCELLED);
        messageRepo.save(m);

        Outreach o = m.getOutreach();
        if (o.getStatus() == OutreachStatus.QUEUED) {
            o.setStatus(o.getFirstSentAt() == null ? OutreachStatus.DRAFT
                    : (o.getOpenedAt() != null ? OutreachStatus.OPENED : OutreachStatus.SENT));
            outreachRepo.save(o);
        }
    }

    @Transactional
    public Views.MessageView retryMessage(Long messageId) {
        EmailMessage m = messageRepo.findById(messageId)
                .orElseThrow(() -> ApiException.notFound("Message " + messageId + " not found"));
        if (m.getStatus() != MessageStatus.FAILED && m.getStatus() != MessageStatus.CANCELLED) {
            throw ApiException.conflict("Only failed or cancelled emails can be re-queued.");
        }
        m.setStatus(MessageStatus.QUEUED);
        m.setAttempts(0);
        m.setLastError(null);
        m.setScheduledAt(Instant.now());
        m.setQueuedAt(Instant.now());
        messageRepo.save(m);

        Outreach o = m.getOutreach();
        o.setStatus(OutreachStatus.QUEUED);
        outreachRepo.save(o);
        return Views.of(m);
    }

    // ---- called by the dispatcher -----------------------------------------

    @Transactional
    public void markSent(Long messageId, String rfcMessageId) {
        EmailMessage m = messageRepo.findById(messageId).orElse(null);
        if (m == null) {
            return;
        }
        Instant now = Instant.now();
        m.setStatus(MessageStatus.SENT);
        m.setSentAt(now);
        m.setLastError(null);
        m.setMessageId(rfcMessageId);
        messageRepo.save(m);

        Outreach o = m.getOutreach();
        o.setLastSentAt(now);
        if (m.getKind() == MessageKind.INITIAL) {
            o.setFirstSentAt(now);
        } else {
            o.setFollowUpsSent(o.getFollowUpsSent() + 1);
        }
        if (o.getStatus() != OutreachStatus.REPLIED && o.getStatus() != OutreachStatus.CLOSED) {
            o.setStatus(o.getOpenedAt() != null ? OutreachStatus.OPENED : OutreachStatus.SENT);
        }
        o.setNextFollowUpAt(computeNextFollowUp(o, now));
        outreachRepo.save(o);
    }

    @Transactional
    public void markAttemptFailed(Long messageId, String error, int maxAttempts) {
        EmailMessage m = messageRepo.findById(messageId).orElse(null);
        if (m == null) {
            return;
        }
        m.setAttempts(m.getAttempts() + 1);
        m.setLastError(trimError(error));

        if (m.getAttempts() >= maxAttempts) {
            m.setStatus(MessageStatus.FAILED);
            Outreach o = m.getOutreach();
            if (o.getStatus() == OutreachStatus.QUEUED) {
                o.setStatus(o.getFirstSentAt() == null ? OutreachStatus.FAILED
                        : (o.getOpenedAt() != null ? OutreachStatus.OPENED : OutreachStatus.SENT));
                outreachRepo.save(o);
            }
        } else {
            // Linear backoff before the next attempt, on top of the normal pacing.
            m.setStatus(MessageStatus.QUEUED);
            m.setScheduledAt(Instant.now().plusSeconds(60L * m.getAttempts()));
        }
        messageRepo.save(m);
    }

    @Transactional
    public boolean recordOpen(String token) {
        Optional<EmailMessage> found = messageRepo.findByTrackingToken(token);
        if (found.isEmpty()) {
            return false;
        }
        EmailMessage m = found.get();
        Instant now = Instant.now();
        if (m.getOpenedAt() == null) {
            m.setOpenedAt(now);
        }
        m.setOpenCount(m.getOpenCount() + 1);
        messageRepo.save(m);

        Outreach o = m.getOutreach();
        if (o.getOpenedAt() == null) {
            o.setOpenedAt(now);
        }
        o.setOpenCount(o.getOpenCount() + 1);
        if (o.getStatus() == OutreachStatus.SENT) {
            o.setStatus(OutreachStatus.OPENED);
        }
        outreachRepo.save(o);
        return true;
    }

    /** Used by the IMAP watcher: match an inbound sender to a live thread. */
    @Transactional
    public int registerInboundReply(String fromEmail, Instant when) {
        List<Outreach> matches = outreachRepo.findByRecipientEmailIgnoreCase(normaliseEmail(fromEmail));
        int updated = 0;
        for (Outreach o : matches) {
            boolean live = o.getStatus() == OutreachStatus.SENT
                    || o.getStatus() == OutreachStatus.OPENED
                    || o.getStatus() == OutreachStatus.QUEUED;
            if (live && o.getFirstSentAt() != null && when.isAfter(o.getFirstSentAt())) {
                applyReply(o, when);
                outreachRepo.save(o);
                updated++;
            }
        }
        return updated;
    }

    // ---- helpers -----------------------------------------------------------

    private EmailMessage queueInitialFor(Outreach o, Long templateId, String subjectOverride,
                                         String bodyOverride, Instant scheduledAt, AppSettings s) {
        Map<String, String> vars = renderer.variables(o, s);
        String subject;
        String body;

        if (bodyOverride != null && !bodyOverride.isBlank()) {
            subject = renderer.render(subjectOverride == null ? "" : subjectOverride, vars);
            body = renderer.render(bodyOverride, vars);
        } else {
            EmailTemplate t = resolveTemplate(
                    templateId != null ? templateId : o.getInitialTemplateId(), TemplateKind.INITIAL);
            o.setInitialTemplateId(t.getId());
            subject = renderer.render(t.getSubject(), vars);
            body = renderer.render(t.getBodyHtml(), vars);
        }
        if (subject.isBlank()) {
            subject = "Hello from " + (s.getFromName() == null || s.getFromName().isBlank()
                    ? "a candidate" : s.getFromName());
        }

        EmailMessage m = new EmailMessage();
        m.setOutreach(o);
        m.setKind(MessageKind.INITIAL);
        m.setSequenceNo(0);
        m.setToEmail(o.getRecipientEmail());
        m.setSubject(subject);
        m.setBodyHtml(body);
        m.setTrackingToken(newToken());
        m.setStatus(MessageStatus.QUEUED);
        m.setQueuedAt(Instant.now());
        m.setScheduledAt(scheduledAt != null ? scheduledAt : Instant.now());
        messageRepo.save(m);

        o.setStatus(OutreachStatus.QUEUED);
        outreachRepo.save(o);
        return m;
    }

    /**
     * Follow-ups reuse the opening subject with a single "Re:" prefix so the
     * mail client threads them into the same conversation.
     */
    private String followUpSubject(Outreach o, EmailTemplate t, Map<String, String> vars) {
        String original = messageRepo.findByOutreachIdOrderBySequenceNoAscIdAsc(o.getId()).stream()
                .filter(m -> m.getKind() == MessageKind.INITIAL)
                .map(EmailMessage::getSubject)
                .findFirst()
                .orElse(null);

        if (original == null || original.isBlank()) {
            String rendered = renderer.render(t.getSubject(), vars);
            return rendered.isBlank() ? "Following up" : rendered;
        }
        return original.regionMatches(true, 0, "re:", 0, 3) ? original : "Re: " + original;
    }

    private Instant computeNextFollowUp(Outreach o, Instant from) {
        if (!o.isAutoFollowUp() || o.getFollowUpsSent() >= o.getMaxFollowUps()) {
            return null;
        }
        return from.plus(Math.max(1, o.getFollowUpIntervalDays()), ChronoUnit.DAYS);
    }

    private EmailTemplate resolveTemplate(Long id, TemplateKind kind) {
        if (id != null) {
            return templateRepo.findById(id)
                    .orElseThrow(() -> ApiException.notFound("Template " + id + " no longer exists."));
        }
        return templateRepo.findFirstByKindAndIsDefaultTrue(kind)
                .or(() -> templateRepo.findFirstByKindOrderByIdAsc(kind))
                .orElseThrow(() -> ApiException.badRequest(
                        "No " + kind.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + " template exists yet. Create one on the Templates tab."));
    }

    private void applyForm(Outreach o, OutreachForm f, AppSettings s, boolean isNew) {
        if (f.getRecipientEmail() != null) {
            o.setRecipientEmail(normaliseEmail(f.getRecipientEmail()));
        }
        if (f.getRecipientName() != null || isNew) {
            o.setRecipientName(blankToNull(f.getRecipientName()));
        }
        if (f.getCompany() != null || isNew) {
            o.setCompany(blankToNull(f.getCompany()));
        }
        if (f.getPosition() != null || isNew) {
            o.setPosition(blankToNull(f.getPosition()));
        }
        if (f.getNotes() != null || isNew) {
            o.setNotes(blankToNull(f.getNotes()));
        }
        if (f.getInitialTemplateId() != null) {
            o.setInitialTemplateId(f.getInitialTemplateId());
        }
        if (f.getFollowUpTemplateId() != null) {
            o.setFollowUpTemplateId(f.getFollowUpTemplateId());
        }

        int interval = f.getFollowUpIntervalDays() != null
                ? f.getFollowUpIntervalDays()
                : (isNew ? s.getDefaultFollowUpIntervalDays() : o.getFollowUpIntervalDays());
        o.setFollowUpIntervalDays(Math.min(90, Math.max(1, interval)));

        int max = f.getMaxFollowUps() != null
                ? f.getMaxFollowUps()
                : (isNew ? s.getDefaultMaxFollowUps() : o.getMaxFollowUps());
        o.setMaxFollowUps(Math.min(10, Math.max(0, max)));

        if (f.getAutoFollowUp() != null) {
            o.setAutoFollowUp(f.getAutoFollowUp());
        }
        if (!isNew) {
            o.setNextFollowUpAt(computeNextFollowUp(o,
                    o.getLastSentAt() != null ? o.getLastSentAt() : Instant.now()));
        }
    }

    private boolean matches(Outreach o, String q) {
        return contains(o.getRecipientEmail(), q) || contains(o.getRecipientName(), q)
                || contains(o.getCompany(), q) || contains(o.getPosition(), q);
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private boolean hasPendingMessage(Long outreachId) {
        return !messageRepo.findByOutreachIdAndStatus(outreachId, MessageStatus.QUEUED).isEmpty();
    }

    private boolean hasLiveThread(String email) {
        return outreachRepo.findByRecipientEmailIgnoreCase(email).stream()
                .anyMatch(o -> o.getStatus() != OutreachStatus.CLOSED
                        && o.getStatus() != OutreachStatus.FAILED);
    }

    private Outreach require(Long id) {
        return outreachRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Outreach " + id + " not found"));
    }

    private static OutreachStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return OutreachStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String trimError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 990 ? error.substring(0, 990) + "…" : error;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
