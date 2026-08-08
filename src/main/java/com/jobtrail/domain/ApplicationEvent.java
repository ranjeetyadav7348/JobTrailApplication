package com.jobtrail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One inbound email that said something about an application. The scanner
 * writes exactly one of these per message it recognises, keyed on the RFC
 * {@code Message-ID} so re-scanning the same window is a no-op.
 */
@Entity
@Table(name = "application_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_message_id", columnNames = "message_id"),
        indexes = {
                @Index(name = "idx_event_application", columnList = "application_id"),
                @Index(name = "idx_event_received", columnList = "received_at"),
                @Index(name = "idx_event_kind", columnList = "kind")
        })
@Getter
@Setter
public class ApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private JobApplication application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ApplicationEventKind kind = ApplicationEventKind.OTHER;

    /**
     * The mail's {@code Message-ID}, or a synthetic digest when the sender
     * omitted one. Unique — this is what makes re-scanning idempotent.
     */
    @Column(name = "message_id", nullable = false, length = 400)
    private String messageId;

    @Column(length = 500)
    private String subject;

    @Column(name = "from_address", length = 254)
    private String fromAddress;

    @Column(name = "from_name", length = 200)
    private String fromName;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    /** First ~600 characters of the plain-text body, for the timeline. */
    @Column(length = 1000)
    private String snippet;

    /** The link worth clicking — test link, scheduling link, portal link. */
    @Column(name = "action_url", length = 1000)
    private String actionUrl;

    /** Deadline parsed out of an assessment invite, when one was stated. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    /** 0..1 — how sure the classifier was. Low-confidence rows are flagged in the UI. */
    @Column(nullable = false)
    private double confidence = 0d;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
