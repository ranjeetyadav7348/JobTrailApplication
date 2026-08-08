package com.jobtrail.service;

import com.jobtrail.config.JobTrailProperties;
import com.jobtrail.domain.AppSettings;
import com.jobtrail.repo.AppSettingsRepository;
import com.jobtrail.web.dto.SettingsForm;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final AppSettingsRepository repo;
    private final JobTrailProperties props;
    private final MailSenderProvider mailSenderProvider;
    private final Environment env;

    @Transactional
    public AppSettings get() {
        return repo.findById(AppSettings.SINGLETON_ID).orElseGet(this::createFromEnvironment);
    }

    public int intervalFloorSeconds() {
        return Math.max(1, props.getMinIntervalFloorSeconds());
    }

    /**
     * Gap to wait after a send before the next one is allowed: the configured
     * minimum (never below the app floor) plus a random jitter, so the outgoing
     * pattern does not look machine-generated to the receiving provider.
     */
    public long nextGapMillis(AppSettings s) {
        int base = Math.max(intervalFloorSeconds(), s.getMinIntervalSeconds());
        int jitter = Math.max(0, s.getJitterSeconds());
        int extra = jitter == 0 ? 0 : ThreadLocalRandom.current().nextInt(jitter + 1);
        return (base + extra) * 1000L;
    }

    @Transactional
    public AppSettings update(SettingsForm f) {
        AppSettings s = get();

        s.setFromName(str(f.getFromName(), s.getFromName()));
        s.setFromEmail(trim(str(f.getFromEmail(), s.getFromEmail())));
        s.setReplyTo(trim(str(f.getReplyTo(), s.getReplyTo())));
        s.setSignatureHtml(str(f.getSignatureHtml(), s.getSignatureHtml()));
        s.setAttachmentPath(trim(str(f.getAttachmentPath(), s.getAttachmentPath())));
        s.setAttachmentName(trim(str(f.getAttachmentName(), s.getAttachmentName())));
        s.setResumePath(trim(str(f.getResumePath(), s.getResumePath())));

        s.setSmtpHost(trim(str(f.getSmtpHost(), s.getSmtpHost())));
        s.setSmtpPort(clamp(num(f.getSmtpPort(), s.getSmtpPort()), 1, 65535));
        s.setSmtpUsername(trim(str(f.getSmtpUsername(), s.getSmtpUsername())));
        // Blank means "keep the stored secret" — the UI never receives it back.
        if (f.getSmtpPassword() != null && !f.getSmtpPassword().isBlank()) {
            s.setSmtpPassword(f.getSmtpPassword());
        }
        s.setSmtpStartTls(bool(f.getSmtpStartTls(), s.isSmtpStartTls()));
        s.setSmtpSsl(bool(f.getSmtpSsl(), s.isSmtpSsl()));
        s.setSmtpAuth(bool(f.getSmtpAuth(), s.isSmtpAuth()));

        s.setMinIntervalSeconds(clamp(num(f.getMinIntervalSeconds(), s.getMinIntervalSeconds()),
                intervalFloorSeconds(), 3600));
        s.setJitterSeconds(clamp(num(f.getJitterSeconds(), s.getJitterSeconds()), 0, 600));
        s.setDailySendLimit(clamp(num(f.getDailySendLimit(), s.getDailySendLimit()), 1, 5000));
        s.setSendingPaused(bool(f.getSendingPaused(), s.isSendingPaused()));
        s.setSendWindowEnabled(bool(f.getSendWindowEnabled(), s.isSendWindowEnabled()));
        s.setSendWindowStartHour(clamp(num(f.getSendWindowStartHour(), s.getSendWindowStartHour()), 0, 23));
        s.setSendWindowEndHour(clamp(num(f.getSendWindowEndHour(), s.getSendWindowEndHour()), 1, 24));
        s.setMaxAttempts(clamp(num(f.getMaxAttempts(), s.getMaxAttempts()), 1, 10));

        s.setTrackOpens(bool(f.getTrackOpens(), s.isTrackOpens()));

        s.setImapEnabled(bool(f.getImapEnabled(), s.isImapEnabled()));
        s.setImapHost(trim(str(f.getImapHost(), s.getImapHost())));
        s.setImapPort(clamp(num(f.getImapPort(), s.getImapPort()), 1, 65535));
        s.setImapUsername(trim(str(f.getImapUsername(), s.getImapUsername())));
        if (f.getImapPassword() != null && !f.getImapPassword().isBlank()) {
            s.setImapPassword(f.getImapPassword());
        }
        s.setImapFolder(str(f.getImapFolder(), s.getImapFolder()));
        s.setImapPollMinutes(clamp(num(f.getImapPollMinutes(), s.getImapPollMinutes()), 1, 1440));

        s.setScanEnabled(bool(f.getScanEnabled(), s.isScanEnabled()));
        s.setScanDays(clamp(num(f.getScanDays(), s.getScanDays()), 1, 365));
        s.setScanFolders(str(f.getScanFolders(), s.getScanFolders()));
        s.setAlertPopups(bool(f.getAlertPopups(), s.isAlertPopups()));
        s.setGhostAfterDays(clamp(num(f.getGhostAfterDays(), s.getGhostAfterDays()), 3, 180));

        s.setAiEnabled(bool(f.getAiEnabled(), s.isAiEnabled()));
        s.setAiMaxCallsPerScan(clamp(num(f.getAiMaxCallsPerScan(), s.getAiMaxCallsPerScan()), 0, 2000));

        s.setDefaultFollowUpIntervalDays(
                clamp(num(f.getDefaultFollowUpIntervalDays(), s.getDefaultFollowUpIntervalDays()), 1, 90));
        s.setDefaultMaxFollowUps(clamp(num(f.getDefaultMaxFollowUps(), s.getDefaultMaxFollowUps()), 0, 10));

        AppSettings saved = repo.save(s);
        mailSenderProvider.invalidate();
        return saved;
    }

    /**
     * Remembers when the mailbox was last read, so the next scheduled pass can
     * be incremental instead of re-reading a month of mail.
     */
    @Transactional
    public void recordScan(Instant at) {
        AppSettings s = get();
        s.setLastScanAt(at);
        repo.save(s);
    }

    @Transactional
    public AppSettings setPaused(boolean paused) {
        AppSettings s = get();
        s.setSendingPaused(paused);
        return repo.save(s);
    }

    private AppSettings createFromEnvironment() {
        AppSettings s = new AppSettings();
        s.setId(AppSettings.SINGLETON_ID);

        // An environment value wins only when it actually has something in it.
        // A blank one must fall back to the entity default rather than overwrite
        // it, otherwise an unset SMTP_USERNAME/SMTP_PASSWORD leaves the fresh row
        // unconfigured even though the defaults are perfectly good.
        String host = firstNonBlank(env.getProperty("spring.mail.host"), s.getSmtpHost());
        String username = firstNonBlank(env.getProperty("spring.mail.username"), s.getSmtpUsername());
        String password = firstNonBlank(env.getProperty("spring.mail.password"), s.getSmtpPassword());
        Integer port = env.getProperty("spring.mail.port", Integer.class, 587);

        if (!host.isBlank()) {
            s.setSmtpHost(host);
        }
        s.setSmtpPort(port);
        s.setSmtpUsername(username);
        s.setSmtpPassword(password);
        s.setSmtpSsl(port != null && port == 465);
        s.setSmtpStartTls(port == null || port != 465);
        if (username.contains("@")) {
            s.setFromEmail(username);
            s.setImapUsername(username);
            s.setImapPassword(password);
        }

        // Where the CV lives differs per deployment — a local path in
        // development, a mounted file in a container — so it is seeded from the
        // environment rather than carried as a default in the entity.
        String resume = firstNonBlank(env.getProperty("RESUME_PATH"), "");
        if (!resume.isBlank()) {
            s.setResumePath(resume);
            s.setAttachmentPath(resume);
        }
        s.setMinIntervalSeconds(Math.max(intervalFloorSeconds(), s.getMinIntervalSeconds()));
        return repo.save(s);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static String str(String incoming, String current) {
        return incoming != null ? incoming : current;
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    private static int num(Integer incoming, int current) {
        return incoming != null ? incoming : current;
    }

    private static boolean bool(Boolean incoming, boolean current) {
        return incoming != null ? incoming : current;
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }
}
