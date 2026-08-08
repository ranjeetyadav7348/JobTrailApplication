package com.jobtrail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

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

    // Blank by default, deliberately. These used to carry a real address and a
    // real Gmail app password as field initialisers, which meant a live
    // credential sat in version control and in every built artefact. They are
    // seeded from the environment on first boot instead — see
    // SettingsService.createFromEnvironment — and edited from the Settings tab
    // thereafter.
    @Column(name = "smtp_username", length = 254)
    private String smtpUsername = "";

    @Column(name = "smtp_password", length = 500)
    private String smtpPassword = "";

    @Column(name = "smtp_start_tls", nullable = false)
    private boolean smtpStartTls = true;

    /** Implicit TLS (SMTPS, usually port 465). Mutually exclusive with STARTTLS. */
    @Column(name = "smtp_ssl", nullable = false)
    private boolean smtpSsl = false;

    @Column(name = "smtp_auth", nullable = false)
    private boolean smtpAuth = true;

    // ---- attachment ---------------------------------------------------------
    /**
     * Absolute path to a file attached to every outgoing email. Blank = none.
     *
     * <p>Seeded from {@code RESUME_PATH} on first boot rather than hard-coded,
     * because a developer's local Windows path means nothing inside a container
     * — there the résumé is mounted at a fixed location and that variable points
     * at it.
     */
    @Column(name = "attachment_path", length = 500)
    private String attachmentPath = "";

    /** Filename the recipient sees. Blank = use the file's own name. */
    @Column(name = "attachment_name", length = 200)
    private String attachmentName = "";

    // ---- knowledge base -----------------------------------------------------
    /**
     * The CV the assistant reads to ground its decisions. Blank falls back to
     * {@link #attachmentPath}, which is almost always the same file — but the
     * two are separate settings because they answer to different needs: the
     * attachment is what employers receive, and this is what the model reasons
     * from. Someone maintaining a plain-text CV for better extraction should be
     * able to point this at it without changing what gets emailed out.
     *
     * <p>Nullable rather than defaulted, because {@code ddl-auto=update} adds
     * this column to an existing single-row table and a NOT NULL column with no
     * database default cannot be backfilled.
     */
    @Column(name = "resume_path", length = 500)
    private String resumePath = "";

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

    // ---- application tracking (mailbox scan) --------------------------------
    // These carry @ColumnDefault as well as a field initialiser. The schema is
    // managed with ddl-auto=update against an existing single-row table, and
    // "alter table add column ... not null" is rejected unless the column has a
    // database default to backfill that row with.
    /** Master switch for reading the mailbox to discover job applications. */
    @Column(name = "scan_enabled", nullable = false)
    @ColumnDefault("false")
    private boolean scanEnabled = false;

    /** How far back a full scan reaches. The default covers "last month". */
    @Column(name = "scan_days", nullable = false)
    @ColumnDefault("30")
    private int scanDays = 30;

    /** Comma-separated folders to read. Gmail's "All Mail" is [Gmail]/All Mail. */
    @Column(name = "scan_folders", length = 400)
    @ColumnDefault("'INBOX'")
    private String scanFolders = "INBOX";

    /** Raise a browser popup for assessment, interview and offer mail. */
    @Column(name = "alert_popups", nullable = false)
    @ColumnDefault("true")
    private boolean alertPopups = true;

    /** Silence on a live application for this long marks it ghosted. */
    @Column(name = "ghost_after_days", nullable = false)
    @ColumnDefault("30")
    private int ghostAfterDays = 30;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    // ---- AI triage -----------------------------------------------------------
    /** Ask a model about mail the keyword rules cannot settle. Off by default. */
    @Column(name = "ai_enabled", nullable = false)
    @ColumnDefault("false")
    private boolean aiEnabled = false;

    /**
     * Hard ceiling on model calls per scan. A month of mail can be thousands of
     * messages; without a cap one scan of a busy mailbox could run up a
     * surprising bill.
     */
    @Column(name = "ai_max_calls_per_scan", nullable = false)
    @ColumnDefault("100")
    private int aiMaxCallsPerScan = 100;

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
        return readableFile(attachmentPath);
    }

    public boolean attachmentReady() {
        return resolvedAttachment() != null;
    }

    /**
     * The CV to index, or {@code null} when neither setting points at a readable
     * file. Falls back to the email attachment, since that is already configured
     * and is nearly always the same document.
     */
    public Path resolvedResume() {
        Path explicit = readableFile(resumePath);
        return explicit != null ? explicit : resolvedAttachment();
    }

    public boolean resumeReady() {
        return resolvedResume() != null;
    }

    private static Path readableFile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(value.trim());
            return Files.isReadable(p) && !Files.isDirectory(p) ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The folders to read, already split and trimmed. Never empty. */
    public java.util.List<String> scanFolderList() {
        if (scanFolders == null || scanFolders.isBlank()) {
            return java.util.List.of("INBOX");
        }
        java.util.List<String> names = java.util.Arrays.stream(scanFolders.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return names.isEmpty() ? java.util.List.of("INBOX") : names;
    }

    /** Scanning needs the same IMAP credentials reply detection uses. */
    public boolean imapConfigured() {
        return imapHost != null && !imapHost.isBlank()
                && imapUsername != null && !imapUsername.isBlank()
                && imapPassword != null && !imapPassword.isBlank();
    }

    public boolean smtpConfigured() {
        return smtpHost != null && !smtpHost.isBlank()
                && fromEmail != null && !fromEmail.isBlank()
                && (!smtpAuth || (smtpUsername != null && !smtpUsername.isBlank()
                                  && smtpPassword != null && !smtpPassword.isBlank()));
    }
}
