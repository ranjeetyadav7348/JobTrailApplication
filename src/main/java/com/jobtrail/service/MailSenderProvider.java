package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds a {@link JavaMailSenderImpl} from the settings row and caches it until
 * one of the SMTP fields actually changes, so editing the connection in the UI
 * takes effect without a restart.
 */
@Component
public class MailSenderProvider {

    private String cachedFingerprint;
    private JavaMailSenderImpl cached;

    public synchronized JavaMailSenderImpl get(AppSettings settings) {
        String fingerprint = fingerprint(settings);
        if (cached == null || !fingerprint.equals(cachedFingerprint)) {
            cached = build(settings);
            cachedFingerprint = fingerprint;
        }
        return cached;
    }

    /** A throwaway sender, used by the "test connection" button. */
    public JavaMailSenderImpl build(AppSettings s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(nullSafe(s.getSmtpHost()));
        sender.setPort(s.getSmtpPort());
        sender.setUsername(nullSafe(s.getSmtpUsername()));
        sender.setPassword(nullSafe(s.getSmtpPassword()));
        sender.setDefaultEncoding("UTF-8");

        boolean implicitTls = s.isSmtpSsl();
        boolean startTls = s.isSmtpStartTls() && !implicitTls;

        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(s.isSmtpAuth()));
        p.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        p.put("mail.smtp.starttls.required", String.valueOf(startTls));
        if (implicitTls) {
            p.put("mail.smtp.ssl.enable", "true");
        }
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "25000");
        p.put("mail.smtp.writetimeout", "25000");
        return sender;
    }

    public synchronized void invalidate() {
        cached = null;
        cachedFingerprint = null;
    }

    private String fingerprint(AppSettings s) {
        return String.join("|",
                nullSafe(s.getSmtpHost()),
                String.valueOf(s.getSmtpPort()),
                nullSafe(s.getSmtpUsername()),
                String.valueOf(nullSafe(s.getSmtpPassword()).hashCode()),
                String.valueOf(s.isSmtpStartTls()),
                String.valueOf(s.isSmtpSsl()),
                String.valueOf(s.isSmtpAuth()));
    }

    private String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
