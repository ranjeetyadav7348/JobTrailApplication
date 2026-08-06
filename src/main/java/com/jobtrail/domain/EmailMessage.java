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
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One physical email. Rows sit in {@link MessageStatus#QUEUED} until the
 * dispatcher picks them up, which it does no faster than the configured
 * minimum interval so the mailbox provider never sees a burst.
 */
@Entity
@Table(name = "email_message", indexes = {
        @Index(name = "idx_msg_status_scheduled", columnList = "status, scheduled_at"),
        @Index(name = "idx_msg_outreach", columnList = "outreach_id"),
        @Index(name = "idx_msg_token", columnList = "tracking_token")
})
@Getter
@Setter
public class EmailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outreach_id", nullable = false)
    private Outreach outreach;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageKind kind = MessageKind.INITIAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status = MessageStatus.QUEUED;

    /** 0 for the opening email, then 1..n for each follow-up. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo = 0;

    @Column(name = "to_email", nullable = false, length = 254)
    private String toEmail;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "body_html", nullable = false, length = 20000)
    private String bodyHtml;

    /** The dispatcher will not send before this instant. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt = Instant.now();

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "tracking_token", length = 64, unique = true)
    private String trackingToken;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "open_count", nullable = false)
    private int openCount = 0;

    /** RFC 5322 Message-ID we stamped on the outgoing mail, used to thread follow-ups. */
    @Column(name = "message_id", length = 255)
    private String messageId;
}
