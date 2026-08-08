package com.jobtrail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Something that wants your attention. The browser polls for unacknowledged
 * rows and raises a popup for the critical ones.
 *
 * <p>{@code dedupeKey} keeps a re-scan or a repeated reminder from producing a
 * second popup for the same thing.
 */
@Entity
@Table(name = "alert",
        uniqueConstraints = @UniqueConstraint(name = "uk_alert_dedupe", columnNames = "dedupe_key"),
        indexes = @Index(name = "idx_alert_ack", columnList = "acknowledged_at"))
@Getter
@Setter
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertKind kind;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 1000)
    private String body;

    /** Where the button in the popup goes — usually the test link. */
    @Column(name = "action_url", length = 1000)
    private String actionUrl;

    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "dedupe_key", nullable = false, length = 420)
    private String dedupeKey;

    /** Copied from the source event so the popup can show the countdown. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Set once you dismiss it. Null means the popup is still owed to you. */
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    public boolean isUnread() {
        return acknowledgedAt == null;
    }
}
