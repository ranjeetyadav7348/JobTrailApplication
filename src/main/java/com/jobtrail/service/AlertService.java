package com.jobtrail.service;

import com.jobtrail.domain.Alert;
import com.jobtrail.domain.AlertKind;
import com.jobtrail.domain.ApplicationEvent;
import com.jobtrail.domain.JobApplication;
import com.jobtrail.repo.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Raises the things worth interrupting you for and remembers whether you have
 * seen them. Every alert carries a dedupe key, so a re-scan of the same mailbox
 * window — or a deadline reminder that fires on two consecutive days — cannot
 * produce a second popup for something you already dismissed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    /** How close a deadline has to be before it is worth a warning. */
    private static final Duration DEADLINE_WARN_WITHIN = Duration.ofHours(36);

    private final AlertRepository repo;

    /**
     * Called for every stored event. Only assessment, interview and offer mail
     * produces an alert — everything else is visible on the dashboard without
     * demanding attention.
     *
     * @return the new alert, or empty when this kind is not alert-worthy or was
     *         already raised
     */
    @Transactional
    public Optional<Alert> raiseForEvent(JobApplication application, ApplicationEvent event) {
        if (!event.getKind().alertWorthy()) {
            return Optional.empty();
        }
        AlertKind kind = switch (event.getKind()) {
            case ASSESSMENT_INVITE -> AlertKind.ASSESSMENT;
            case INTERVIEW_INVITE -> AlertKind.INTERVIEW;
            case OFFER -> AlertKind.OFFER;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }

        String where = application.getCompany()
                + (application.getRoleTitle() == null ? "" : " · " + application.getRoleTitle());

        Alert alert = new Alert();
        alert.setKind(kind);
        alert.setTitle(kind.label() + " — " + where);
        alert.setBody(event.getSubject());
        alert.setActionUrl(event.getActionUrl());
        alert.setApplicationId(application.getId());
        alert.setDeadlineAt(event.getDeadlineAt());
        alert.setDedupeKey("event:" + event.getMessageId());
        return save(alert);
    }

    /** Warns once per day that an assessment is about to expire. */
    @Transactional
    public Optional<Alert> raiseDeadlineWarning(JobApplication application) {
        Instant due = application.getAssessmentDueAt();
        if (due == null || due.isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (Duration.between(Instant.now(), due).compareTo(DEADLINE_WARN_WITHIN) > 0) {
            return Optional.empty();
        }

        Alert alert = new Alert();
        alert.setKind(AlertKind.DEADLINE);
        alert.setTitle("Assessment closes soon — " + application.getCompany());
        alert.setBody("The assessment for " + application.getCompany()
                + " closes on " + due.atZone(ZoneId.systemDefault()).toLocalDateTime() + ".");
        alert.setActionUrl(application.getAssessmentUrl());
        alert.setApplicationId(application.getId());
        alert.setDeadlineAt(due);
        alert.setDedupeKey("deadline:" + application.getId() + ':'
                + LocalDate.ofInstant(due, ZoneId.systemDefault()));
        return save(alert);
    }

    /** One scan-failure alert per day, so a broken mailbox does not spam you. */
    @Transactional
    public Optional<Alert> raiseScanError(String detail) {
        Alert alert = new Alert();
        alert.setKind(AlertKind.SCAN_ERROR);
        alert.setTitle("Mailbox scan failed");
        alert.setBody(detail);
        alert.setDedupeKey("scan:" + LocalDate.now());
        return save(alert);
    }

    /**
     * Insert-if-absent. The unique constraint is the real guard — two scans
     * running at once would both pass a read check, so the caught violation is
     * the mechanism, not a fallback.
     */
    private Optional<Alert> save(Alert alert) {
        if (repo.existsByDedupeKey(alert.getDedupeKey())) {
            return Optional.empty();
        }
        try {
            return Optional.of(repo.save(alert));
        } catch (DataIntegrityViolationException e) {
            log.debug("Alert {} already existed", alert.getDedupeKey());
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<Alert> unread() {
        return repo.findByAcknowledgedAtIsNullOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Alert> recent() {
        return repo.findTop30ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repo.countByAcknowledgedAtIsNull();
    }

    @Transactional
    public boolean acknowledge(Long id) {
        return repo.findById(id)
                .filter(Alert::isUnread)
                .map(a -> {
                    a.setAcknowledgedAt(Instant.now());
                    repo.save(a);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public int acknowledgeAll() {
        return repo.acknowledgeAll(Instant.now());
    }
}
