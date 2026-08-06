package com.jobtrail.service;

import com.jobtrail.config.JobTrailProperties;
import com.jobtrail.domain.AppSettings;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

/**
 * The only place in the app that talks to SMTP.
 * <p>
 * It is a single scheduled loop, so exactly one email is in flight at a time,
 * and after every attempt it parks itself for at least the configured minimum
 * interval (never below {@code jobtrail.min-interval-floor-seconds}, which is
 * 5s). That spacing — plus random jitter, a daily cap and an optional
 * time-of-day window — is what keeps a batch from looking like a blast to the
 * receiving mail provider.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDispatcher {

    private final SendQueueService queue;
    private final OutreachService outreachService;
    private final SettingsService settingsService;
    private final MailSenderProvider mailSenderProvider;
    private final TemplateRenderer renderer;
    private final DispatcherStatus status;
    private final JobTrailProperties props;

    @Scheduled(fixedDelayString = "${jobtrail.dispatcher-poll-ms:1000}", initialDelay = 4000)
    public void pump() {
        try {
            tick();
        } catch (Exception e) {
            log.error("Dispatcher loop failed", e);
            status.set("ERROR", rootMessage(e));
        }
    }

    private void tick() {
        AppSettings s = settingsService.get();

        if (s.isSendingPaused()) {
            status.set("PAUSED", "Sending is paused");
            return;
        }
        if (!s.smtpConfigured()) {
            status.set("NOT_CONFIGURED", "Add your sender address and SMTP details on the Settings tab");
            return;
        }
        if (!status.slotOpen()) {
            status.set("THROTTLED", "Spacing sends — next one in " + status.secondsUntilSlot() + "s");
            return;
        }
        if (!withinSendWindow(s)) {
            status.set("OUTSIDE_WINDOW", String.format("Outside the %02d:00–%02d:00 send window",
                    s.getSendWindowStartHour(), s.getSendWindowEndHour()));
            return;
        }
        long today = queue.sentToday();
        if (today >= s.getDailySendLimit()) {
            status.set("DAILY_LIMIT", "Daily cap of " + s.getDailySendLimit() + " emails reached");
            return;
        }

        Optional<SendQueueService.SendPayload> claimed = queue.claimNext(Instant.now());
        if (claimed.isEmpty()) {
            status.set("IDLE", "Nothing waiting in the queue");
            return;
        }

        SendQueueService.SendPayload p = claimed.get();
        status.set("SENDING", "Sending to " + p.toEmail());
        try {
            String rfcMessageId = send(p, s);
            outreachService.markSent(p.messageId(), rfcMessageId);
            log.info("Sent {} #{} to {}", p.kind(), p.messageId(), p.toEmail());
        } catch (Exception e) {
            String reason = rootMessage(e);
            outreachService.markAttemptFailed(p.messageId(), reason, s.getMaxAttempts());
            log.warn("Send failed for #{} to {}: {}", p.messageId(), p.toEmail(), reason);
        } finally {
            // Hold the line whether the send worked or not: pacing is about the
            // rate we hit the SMTP server, not about how many succeeded.
            long gap = settingsService.nextGapMillis(s);
            status.holdFor(gap);
            status.set("THROTTLED", "Spacing sends — next one in about " + (gap / 1000) + "s");
        }
    }

    /** Builds and transmits one real email; returns the Message-ID we stamped on it. */
    private String send(SendQueueService.SendPayload p, AppSettings s) throws Exception {
        JavaMailSenderImpl sender = mailSenderProvider.get(s);
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(
                mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");

        if (s.getFromName() == null || s.getFromName().isBlank()) {
            helper.setFrom(s.getFromEmail());
        } else {
            helper.setFrom(s.getFromEmail(), s.getFromName());
        }
        if (s.getReplyTo() != null && !s.getReplyTo().isBlank()) {
            helper.setReplyTo(s.getReplyTo());
        }
        helper.setTo(p.toEmail());
        helper.setSubject(p.subject());

        Map<String, String> vars = renderer.variables(s);
        String signature = renderer.render(s.getSignatureHtml(), vars);
        String pixelUrl = (s.isTrackOpens() && p.trackingToken() != null)
                ? trimTrailingSlash(props.getPublicBaseUrl()) + "/t/" + p.trackingToken() + ".gif"
                : null;

        String html = renderer.wrapHtml(p.bodyHtml(), signature, pixelUrl);
        String plain = renderer.toPlainText(renderer.wrapHtml(p.bodyHtml(), signature, null));
        // Sending text + HTML alternatives rather than HTML alone measurably
        // improves how spam filters score the message.
        helper.setText(plain.isBlank() ? " " : plain, html);

        // Must come after setText: MimeMessageHelper builds the multipart tree
        // in that order.
        Path attachment = s.resolvedAttachment();
        if (attachment != null) {
            String shownAs = (s.getAttachmentName() == null || s.getAttachmentName().isBlank())
                    ? attachment.getFileName().toString()
                    : s.getAttachmentName();
            helper.addAttachment(shownAs, attachment.toFile());
        }

        if (p.inReplyToMessageId() != null && !p.inReplyToMessageId().isBlank()) {
            mime.setHeader("In-Reply-To", p.inReplyToMessageId());
            mime.setHeader("References", p.inReplyToMessageId());
        }
        mime.setHeader("X-Mailer", "JobTrail");

        sender.send(mime);

        try {
            return mime.getMessageID();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean withinSendWindow(AppSettings s) {
        if (!s.isSendWindowEnabled()) {
            return true;
        }
        int hour = LocalTime.now().getHour();
        int start = s.getSendWindowStartHour();
        int end = s.getSendWindowEndHour();
        if (start == end) {
            return true;
        }
        return start < end ? (hour >= start && hour < end) : (hour >= start || hour < end);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8080";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** SMTP failures nest the useful text a few levels down. */
    static String rootMessage(Throwable t) {
        Throwable current = t;
        String best = t.getMessage();
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 10) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                best = current.getMessage();
            }
        }
        if (best == null || best.isBlank()) {
            best = t.getClass().getSimpleName();
        }
        return best;
    }
}
