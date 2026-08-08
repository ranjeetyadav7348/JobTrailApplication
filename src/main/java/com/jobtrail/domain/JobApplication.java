package com.jobtrail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * One job you applied for, on one platform. Rows are created by the mailbox
 * scanner when it recognises an application email, and can also be added by
 * hand for anything that never produced a confirmation mail.
 *
 * <p>The individual emails live in {@link ApplicationEvent}; this row carries
 * the rolled-up state so the dashboard can be built without walking events.
 */
@Entity
@Table(name = "job_application",
        uniqueConstraints = @UniqueConstraint(name = "uk_application_dedupe", columnNames = "dedupe_key"),
        indexes = {
                @Index(name = "idx_application_status", columnList = "status"),
                @Index(name = "idx_application_platform", columnList = "platform"),
                @Index(name = "idx_application_applied_at", columnList = "applied_at")
        })
@Getter
@Setter
public class JobApplication {

    /** No inbound mail for this long on a live application means it is probably dead. */
    public static final Duration GHOST_AFTER = Duration.ofDays(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String company = "Unknown";

    @Column(name = "role_title", length = 250)
    private String roleTitle;

    @Column(length = 160)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JobPlatform platform = JobPlatform.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    /**
     * Identity used to fold repeated mail about the same job into one row:
     * platform + normalised company + normalised role. Unique, so two scans of
     * the same mailbox cannot produce duplicates.
     */
    @Column(name = "dedupe_key", nullable = false, length = 420)
    private String dedupeKey;

    /** Address the confirmation came from, kept so you can see who to chase. */
    @Column(name = "source_email", length = 254)
    private String sourceEmail;

    /** Link to the posting or the candidate portal, when the mail carried one. */
    @Column(name = "job_url", length = 1000)
    private String jobUrl;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    /** When the most recent mail about this application arrived. */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    /** First inbound mail that was not the submission confirmation. */
    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    /** Deadline of the outstanding assessment, when one was detected. */
    @Column(name = "assessment_due_at")
    private Instant assessmentDueAt;

    /** Most recent test/assessment link, so the dashboard can offer it directly. */
    @Column(name = "assessment_url", length = 1000)
    private String assessmentUrl;

    @Column(name = "event_count", nullable = false)
    private int eventCount = 0;

    @Column(length = 2000)
    private String notes;

    /** Added by hand rather than discovered by the scanner. */
    @Column(nullable = false)
    private boolean manual = false;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Applies an event to the rolled-up state. Status only moves forward (see
     * {@link ApplicationStatus#advancesFrom}), and the response clock starts on
     * the first mail that is not the submission confirmation.
     */
    public void apply(ApplicationEvent event) {
        Instant at = event.getReceivedAt() == null ? Instant.now() : event.getReceivedAt();

        if (lastEventAt == null || at.isAfter(lastEventAt)) {
            lastEventAt = at;
        }
        if (event.getKind() != ApplicationEventKind.APPLIED
                && (firstResponseAt == null || at.isBefore(firstResponseAt))) {
            firstResponseAt = at;
        }
        if (event.getKind() == ApplicationEventKind.APPLIED && at.isBefore(appliedAt)) {
            appliedAt = at;
        }
        if (event.getKind() == ApplicationEventKind.ASSESSMENT_INVITE) {
            if (event.getActionUrl() != null && !event.getActionUrl().isBlank()) {
                assessmentUrl = event.getActionUrl();
            }
            if (event.getDeadlineAt() != null) {
                assessmentDueAt = event.getDeadlineAt();
            }
        }

        ApplicationStatus implied = event.getKind().impliedStatus();
        if (implied != null && implied.advancesFrom(status)) {
            status = implied;
        }
        eventCount++;
    }

    /** Days since anything happened, or {@code -1} for a terminal application. */
    public long staleDays() {
        if (status.terminal()) {
            return -1;
        }
        Instant since = lastEventAt != null ? lastEventAt : appliedAt;
        return Duration.between(since, Instant.now()).toDays();
    }

    /** True once a live application has been silent past {@link #GHOST_AFTER}. */
    public boolean looksGhosted() {
        return !status.terminal() && staleDays() >= GHOST_AFTER.toDays();
    }

    public static String dedupeKey(JobPlatform platform, String company, String roleTitle) {
        return (platform == null ? JobPlatform.UNKNOWN : platform).name()
                + '|' + normalise(company)
                + '|' + normalise(roleTitle);
    }

    /**
     * Case-, accent- and punctuation-insensitive form used for matching only.
     * Common company suffixes go too, so "Acme Technologies Pvt. Ltd." and
     * "Acme Technologies" are recognised as the same employer.
     */
    public static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9 ]+", " ")
                .replaceAll("\\b(pvt|private|ltd|limited|llp|inc|incorporated|corp|corporation|"
                        + "co|gmbh|plc|technologies|technology|solutions|systems|labs|india)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s.isEmpty() ? normaliseLoose(value) : s;
    }

    /** Fallback for names that are entirely stripped by the suffix rules above. */
    private static String normaliseLoose(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }
}
