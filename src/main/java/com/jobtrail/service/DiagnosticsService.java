package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.web.ApiException;
import com.jobtrail.web.dto.Views;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** The "does my mail setup actually work?" buttons on the Settings tab. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiagnosticsService {

    private final SettingsService settingsService;
    private final MailSenderProvider mailSenderProvider;
    private final TemplateRenderer renderer;
    private final ReplyWatcher replyWatcher;
    private final DispatcherStatus status;

    public Views.ActionResult testSmtp() {
        AppSettings s = settingsService.get();
        if (s.getSmtpHost() == null || s.getSmtpHost().isBlank()) {
            return new Views.ActionResult(false, "Set an SMTP host first.");
        }
        try {
            JavaMailSenderImpl sender = mailSenderProvider.build(s);
            sender.testConnection();
            return new Views.ActionResult(true, "Connected to " + s.getSmtpHost() + ":" + s.getSmtpPort()
                    + (s.isSmtpAuth() ? " as " + s.getSmtpUsername() : " (no auth)"));
        } catch (Exception e) {
            return new Views.ActionResult(false, EmailDispatcher.rootMessage(e));
        }
    }

    /**
     * Sends one real email to an address you choose. It still respects the
     * pacing gap, so this button cannot be used to burst.
     */
    public Views.ActionResult sendTestEmail(String to) {
        if (to == null || !to.contains("@")) {
            throw ApiException.badRequest("Enter a valid address to send the test to.");
        }
        AppSettings s = settingsService.get();
        if (!s.smtpConfigured()) {
            return new Views.ActionResult(false,
                    "Fill in your sender address and SMTP credentials before sending a test.");
        }
        if (!status.slotOpen()) {
            return new Views.ActionResult(false,
                    "Pacing in effect — try again in " + status.secondsUntilSlot() + "s.");
        }

        try {
            JavaMailSenderImpl sender = mailSenderProvider.get(s);
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            if (s.getFromName() == null || s.getFromName().isBlank()) {
                helper.setFrom(s.getFromEmail());
            } else {
                helper.setFrom(s.getFromEmail(), s.getFromName());
            }
            helper.setTo(to.trim());
            helper.setSubject("JobTrail test email");

            String body = "<p>This is a test from <strong>JobTrail</strong>.</p>"
                    + "<p>If it reached you, your SMTP settings are working and outreach "
                    + "will go out spaced at least " + Math.max(settingsService.intervalFloorSeconds(),
                    s.getMinIntervalSeconds()) + " seconds apart.</p>"
                    + "<p style=\"color:#7b8794;font-size:13px\">Sent " + Instant.now() + "</p>";
            String html = renderer.wrapHtml(body, renderer.render(s.getSignatureHtml(),
                    renderer.variables(s)), null);
            helper.setText(renderer.toPlainText(html), html);

            // Attach the same file a real send would, so the test is an honest
            // preview instead of quietly omitting the CV. Must follow setText —
            // the multipart tree is built there.
            java.nio.file.Path attachment = s.resolvedAttachment();
            if (attachment != null) {
                String shownAs = (s.getAttachmentName() == null || s.getAttachmentName().isBlank())
                        ? attachment.getFileName().toString()
                        : s.getAttachmentName();
                helper.addAttachment(shownAs, attachment.toFile());
            }

            mime.setHeader("X-Mailer", "JobTrail");

            sender.send(mime);
            status.holdFor(settingsService.nextGapMillis(s));
            return new Views.ActionResult(true, "Test email sent to " + to.trim() + ".");
        } catch (Exception e) {
            return new Views.ActionResult(false, EmailDispatcher.rootMessage(e));
        }
    }

    public Views.ActionResult testImap() {
        AppSettings s = settingsService.get();
        try {
            int found = replyWatcher.scan(s, Instant.now().minusSeconds(7 * 24 * 3600L));
            return new Views.ActionResult(true, "Inbox reachable. "
                    + (found > 0 ? found + " thread(s) marked as replied." : "No new replies matched."));
        } catch (Exception e) {
            return new Views.ActionResult(false, EmailDispatcher.rootMessage(e));
        }
    }
}
