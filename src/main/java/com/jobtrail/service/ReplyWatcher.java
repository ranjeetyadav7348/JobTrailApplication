package com.jobtrail.service;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.service.scan.ImapConnector;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
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
    private final ImapConnector connector;

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
        int matched = 0;
        Store store = connector.connect(s);
        try {
            Folder folder = connector.openReadOnly(store, s.getImapFolder());
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
            connector.closeQuietly(store);
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
