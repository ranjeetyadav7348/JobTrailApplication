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
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One job-outreach thread: a person at a company you emailed about a role,
 * plus the follow-up policy for that thread.
 */
@Entity
@Table(name = "outreach", indexes = {
        @Index(name = "idx_outreach_email", columnList = "recipient_email"),
        @Index(name = "idx_outreach_status", columnList = "status"),
        @Index(name = "idx_outreach_next_followup", columnList = "next_follow_up_at")
})
@Getter
@Setter
public class Outreach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_name", length = 160)
    private String recipientName;

    @Column(name = "recipient_email", nullable = false, length = 254)
    private String recipientEmail;

    @Column(length = 160)
    private String company;

    /** "position" is a reserved function name in several databases, so it is mapped explicitly. */
    @Column(name = "role_title", length = 160)
    private String position;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutreachStatus status = OutreachStatus.DRAFT;

    @Column(name = "initial_template_id")
    private Long initialTemplateId;

    @Column(name = "follow_up_template_id")
    private Long followUpTemplateId;

    /** Days of silence before the next follow-up is queued. */
    @Column(name = "follow_up_interval_days", nullable = false)
    private int followUpIntervalDays = 4;

    @Column(name = "max_follow_ups", nullable = false)
    private int maxFollowUps = 2;

    @Column(name = "follow_ups_sent", nullable = false)
    private int followUpsSent = 0;

    @Column(name = "auto_follow_up", nullable = false)
    private boolean autoFollowUp = true;

    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;

    @Column(name = "first_sent_at")
    private Instant firstSentAt;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "open_count", nullable = false)
    private int openCount = 0;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** A thread stops generating follow-ups once it is answered, archived or dead. */
    public boolean isFollowUpEligible() {
        return autoFollowUp
                && followUpsSent < maxFollowUps
                && (status == OutreachStatus.SENT || status == OutreachStatus.OPENED);
    }

    public String displayName() {
        if (recipientName != null && !recipientName.isBlank()) {
            return recipientName;
        }
        int at = recipientEmail == null ? -1 : recipientEmail.indexOf('@');
        return at > 0 ? recipientEmail.substring(0, at) : "there";
    }
}
