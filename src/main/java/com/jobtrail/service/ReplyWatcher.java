package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Properties;

/**
 * Optional IMAP poller. When enabled it reads the inbox, matches senders
 * against open outreach threads and marks those as replied, which immediately
 * stops any further follow-up. Off by default — nothing connects to a mailbox
 * unless you turn it on and supply credentials.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplyWatcher {

    private final SettingsService settingsService;
    private final OutreachService outreachService;

    private volatile Instant lastPollAt;
    private volatile Instant lastSuccessAt;
    private volatile String lastError;
    private volatile int lastRepliesFound;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 45_000L)
    public void pollIfDue() {
        AppSettings s = settingsService.get();
        if (!s.isImapEnabled()) {
            return;
        }
        if (lastPollAt != null
                && lastPollAt.plusSeconds(Math.max(1, s.getImapPollMinutes()) * 60L).isAfter(Instant.now())) {
            return;
        }
        lastPollAt = Instant.now();
        try {
            int found = scan(s, sinceInstant());
            lastRepliesFound = found;
            lastSuccessAt = Instant.now();
            lastError = null;
            if (found > 0) {
                log.info("Reply watcher marked {} thread(s) as replied", found);
            }
        } catch (Exception e) {
            lastError = EmailDispatcher.rootMessage(e);
            log.warn("IMAP poll failed: {}", lastError);
        }
    }

    /** Connects, scans and returns how many threads were flipped to REPLIED. */
    public int scan(AppSettings s, Instant since) throws Exception {
        if (s.getImapHost() == null || s.getImapHost().isBlank()
                || s.getImapUsername() == null || s.getImapUsername().isBlank()) {
            throw new IllegalStateException("IMAP host and username are required");
        }

        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.connectiontimeout", "15000");
        p.put("mail.imaps.timeout", "30000");
        Session session = Session.getInstance(p);

        int matched = 0;
        Store store = session.getStore("imaps");
        try {
            store.connect(s.getImapHost(), s.getImapPort(), s.getImapUsername(), s.getImapPassword());
            String folderName = (s.getImapFolder() == null || s.getImapFolder().isBlank())
                    ? "INBOX" : s.getImapFolder();
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                throw new IllegalStateException("Mail folder \"" + folderName + "\" does not exist");
            }
            folder.open(Folder.READ_ONLY);
            try {
                Message[] messages = folder.search(
                        new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
                for (Message m : messages) {
                    matched += inspect(m);
                }
            } finally {
                folder.close(false);
            }
        } finally {
            try {
                store.close();
            } catch (Exception ignored) {
                // closing a dead connection is not worth reporting
            }
        }
        return matched;
    }

    private int inspect(Message m) {
        try {
            Address[] from = m.getFrom();
            if (from == null || from.length == 0) {
                return 0;
            }
            Date when = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
            Instant at = when != null ? when.toInstant() : Instant.now();

            int updated = 0;
            for (Address a : from) {
                if (a instanceof InternetAddress ia && ia.getAddress() != null) {
                    updated += outreachService.registerInboundReply(ia.getAddress(), at);
                }
            }
            return updated;
        } catch (Exception e) {
            log.debug("Could not inspect an inbox message: {}", e.toString());
            return 0;
        }
    }

    /** Re-reading a window of recent mail is cheap and idempotent. */
    private Instant sinceInstant() {
        Instant base = lastSuccessAt != null
                ? lastSuccessAt.minus(1, ChronoUnit.DAYS)
                : Instant.now().minus(7, ChronoUnit.DAYS);
        return base;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getLastRepliesFound() {
        return lastRepliesFound;
    }
}
