package com.jobtrail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Single-row configuration table (id is always {@link #SINGLETON_ID}).
 * Everything here is editable from the Settings tab at runtime; changing SMTP
 * fields rebuilds the mail sender without a restart.
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    // ---- identity on the outgoing mail -------------------------------------
    @Column(name = "from_name", length = 120)
    private String fromName = "";

    @Column(name = "from_email", length = 254)
    private String fromEmail = "";

    @Column(name = "reply_to", length = 254)
    private String replyTo = "";

    @Column(name = "signature_html", length = 4000)
    private String signatureHtml = "";

    // ---- SMTP --------------------------------------------------------------
    @Column(name = "smtp_host", length = 200)
    private String smtpHost = "smtp.gmail.com";

    @Column(name = "smtp_port", nullable = false)
    private int smtpPort = 587;

    @Column(name = "smtp_username", length = 254)
    private String smtpUsername = "yadavranjeet060@gmail.com";

    @Column(name = "smtp_password", length = 500)
    private String smtpPassword = "psrw bthi oymz xflf";

    @Column(name = "smtp_start_tls", nullable = false)
    private boolean smtpStartTls = true;

    /** Implicit TLS (SMTPS, usually port 465). Mutually exclusive with STARTTLS. */
    @Column(name = "smtp_ssl", nullable = false)
    private boolean smtpSsl = false;

    @Column(name = "smtp_auth", nullable = false)
    private boolean smtpAuth = true;

    // ---- attachment ---------------------------------------------------------
    /** Absolute path to a file attached to every outgoing email. Blank = none. */
    @Column(name = "attachment_path", length = 500)
    private String attachmentPath = "C:\\Users\\hp\\Documents\\resume\\Ranjeet_Java_AI_4Exp.pdf";

    /** Filename the recipient sees. Blank = use the file's own name. */
    @Column(name = "attachment_name", length = 200)
    private String attachmentName = "Ranjeet_Yadav_Resume.pdf";

    // ---- pacing / deliverability -------------------------------------------
    /** Minimum seconds between two real sends. Never allowed below the app floor. */
    @Column(name = "min_interval_seconds", nullable = false)
    private int minIntervalSeconds = 8;

    /** Extra random 0..n seconds added on top so the cadence is not robotic. */
    @Column(name = "jitter_seconds", nullable = false)
    private int jitterSeconds = 4;

    @Column(name = "daily_send_limit", nullable = false)
    private int dailySendLimit = 120;

    @Column(name = "sending_paused", nullable = false)
    private boolean sendingPaused = false;

    /** Only dispatch between the two hours below (local time). */
    @Column(name = "send_window_enabled", nullable = false)
    private boolean sendWindowEnabled = false;

    @Column(name = "send_window_start_hour", nullable = false)
    private int sendWindowStartHour = 9;

    @Column(name = "send_window_end_hour", nullable = false)
    private int sendWindowEndHour = 18;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    // ---- tracking ----------------------------------------------------------
    @Column(name = "track_opens", nullable = false)
    private boolean trackOpens = true;

    // ---- reply detection over IMAP -----------------------------------------
    @Column(name = "imap_enabled", nullable = false)
    private boolean imapEnabled = false;

    @Column(name = "imap_host", length = 200)
    private String imapHost = "imap.gmail.com";

    @Column(name = "imap_port", nullable = false)
    private int imapPort = 993;

    @Column(name = "imap_username", length = 254)
    private String imapUsername = "";

    @Column(name = "imap_password", length = 500)
    private String imapPassword = "";

    @Column(name = "imap_folder", length = 120)
    private String imapFolder = "INBOX";

    @Column(name = "imap_poll_minutes", nullable = false)
    private int imapPollMinutes = 10;

    // ---- defaults for new outreach -----------------------------------------
    @Column(name = "default_follow_up_interval_days", nullable = false)
    private int defaultFollowUpIntervalDays = 4;

    @Column(name = "default_max_follow_ups", nullable = false)
    private int defaultMaxFollowUps = 2;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * The attachment file, or {@code null} when none is set or the path no
     * longer points at something readable. Checked per send rather than cached,
     * so moving or deleting the file simply stops it being attached instead of
     * failing the whole email.
     */
    public Path resolvedAttachment() {
        if (attachmentPath == null || attachmentPath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(attachmentPath.trim());
            return Files.isReadable(p) && !Files.isDirectory(p) ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public boolean attachmentReady() {
        return resolvedAttachment() != null;
    }

    public boolean smtpConfigured() {
        return smtpHost != null && !smtpHost.isBlank()
                && fromEmail != null && !fromEmail.isBlank()
                && (!smtpAuth || (smtpUsername != null && !smtpUsername.isBlank()
                                  && smtpPassword != null && !smtpPassword.isBlank()));
    }
}
