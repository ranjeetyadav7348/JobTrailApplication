package com.jobtrail.web.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Editable settings coming from the UI. Every field is a wrapper type: a null
 * means "leave this one alone", which lets the client send partial updates.
 * A blank {@code smtpPassword} / {@code imapPassword} also means "unchanged",
 * because the API never sends real passwords back to the browser.
 */
@Getter
@Setter
public class SettingsForm {

    private String fromName;
    private String fromEmail;
    private String replyTo;
    private String signatureHtml;

    private String attachmentPath;
    private String attachmentName;
    /** CV used to ground AI decisions. Blank falls back to the attachment. */
    private String resumePath;

    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpPassword;
    private Boolean smtpStartTls;
    private Boolean smtpSsl;
    private Boolean smtpAuth;

    private Integer minIntervalSeconds;
    private Integer jitterSeconds;
    private Integer dailySendLimit;
    private Boolean sendingPaused;
    private Boolean sendWindowEnabled;
    private Integer sendWindowStartHour;
    private Integer sendWindowEndHour;
    private Integer maxAttempts;

    private Boolean trackOpens;

    private Boolean imapEnabled;
    private String imapHost;
    private Integer imapPort;
    private String imapUsername;
    private String imapPassword;
    private String imapFolder;
    private Integer imapPollMinutes;

    private Boolean scanEnabled;
    private Integer scanDays;
    private String scanFolders;
    private Boolean alertPopups;
    private Integer ghostAfterDays;

    private Boolean aiEnabled;
    private Integer aiMaxCallsPerScan;

    private Integer defaultFollowUpIntervalDays;
    private Integer defaultMaxFollowUps;
}
