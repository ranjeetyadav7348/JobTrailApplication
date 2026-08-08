package com.jobtrail.service.scan;

import com.jobtrail.domain.AppSettings;
import com.jobtrail.service.AlertService;
import com.jobtrail.service.ApplicationIngestService;
import com.jobtrail.service.SettingsService;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the mailbox and turns job-related mail into tracked applications.
 *
 * <p>Two entry points share one implementation: a scheduled incremental pass
 * that looks at recent mail, and an on-demand backfill from the UI that reaches
 * back {@code scanDays} (a month by default). Both are safe to run repeatedly —
 * every message is keyed on its {@code Message-ID}, so a second pass over the
 * same window stores nothing new.
 *
 * <p>The mailbox is opened read-only and no flags are written, so scanning
 * never marks your mail as read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailboxScanner {

    /** The incremental pass re-reads a little history to catch late deliveries. */
    private static final Duration INCREMENTAL_OVERLAP = Duration.ofDays(2);

    private final SettingsService settingsService;
    private final ImapConnector connector;
    private final MessageReader reader;
    private final MailClassifier classifier;
    private final AiMailClassifier aiClassifier;
    private final ApplicationIngestService ingestService;
    private final AlertService alertService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Instant lastRunAt;
    private volatile Instant lastSuccessAt;
    private volatile String lastError;
    private volatile ScanReport lastReport;

    /** What a scan did. Returned to the UI so the run is inspectable. */
    public record ScanReport(
            int messagesRead,
            int recognised,
            int stored,
            int newApplications,
            int alertsRaised,
            int foldersRead,
            int aiCalls,
            int promotionalFiltered,
            long durationMs,
            Instant from,
            String error) {

        public boolean ok() {
            return error == null;
        }
    }

    // ---- scheduled incremental pass ----------------------------------------

    @Scheduled(fixedDelay = 60_000L, initialDelay = 75_000L)
    public void pollIfDue() {
        AppSettings s = settingsService.get();
        if (!s.isScanEnabled() || !s.imapConfigured()) {
            return;
        }
        Instant last = s.getLastScanAt();
        if (last != null
                && last.plusSeconds(Math.max(1, s.getImapPollMinutes()) * 60L).isAfter(Instant.now())) {
            return;
        }
        // First run has nothing to be incremental about, so it backfills.
        Instant from = last == null
                ? Instant.now().minus(Math.max(1, s.getScanDays()), ChronoUnit.DAYS)
                : last.minus(INCREMENTAL_OVERLAP);
        run(s, from);
    }

    // ---- on-demand backfill ------------------------------------------------

    /**
     * Full scan over the last {@code days} of mail, triggered from the UI.
     *
     * @throws IllegalStateException if a scan is already in flight or IMAP is unconfigured
     */
    public ScanReport scanNow(Integer days) {
        AppSettings s = settingsService.get();
        if (!s.imapConfigured()) {
            throw new IllegalStateException(
                    "Add your IMAP host, username and password in Settings before scanning.");
        }
        int window = days != null && days > 0 ? Math.min(days, 365) : Math.max(1, s.getScanDays());
        return run(s, Instant.now().minus(window, ChronoUnit.DAYS));
    }

    // ---- the actual work ---------------------------------------------------

    private ScanReport run(AppSettings settings, Instant from) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A scan is already running.");
        }
        long startedAt = System.currentTimeMillis();
        lastRunAt = Instant.now();

        boolean aiAvailable = settings.isAiEnabled() && aiClassifier.available();
        aiClassifier.resetBudget();

        int read = 0;
        int recognised = 0;
        int stored = 0;
        int created = 0;
        int alerted = 0;
        int folders = 0;
        int filtered = 0;
        Store store = null;

        try {
            store = connector.connect(settings);
            for (String folderName : settings.scanFolderList()) {
                Folder folder = null;
                try {
                    folder = connector.openReadOnly(store, folderName);
                    folders++;
                    Message[] messages = folder.search(
                            new ReceivedDateTerm(ComparisonTerm.GE, Date.from(from)));
                    for (Message message : messages) {
                        read++;
                        Ingested outcome = handle(message, settings, aiAvailable);
                        if (outcome.recognised) {
                            recognised++;
                        }
                        if (outcome.stored) {
                            stored++;
                        }
                        if (outcome.created) {
                            created++;
                        }
                        if (outcome.alerted) {
                            alerted++;
                        }
                        if (outcome.filteredAsPromotional) {
                            filtered++;
                        }
                    }
                } catch (Exception e) {
                    // One unreadable folder should not abandon the others.
                    log.warn("Could not scan folder {}: {}", folderName, rootMessage(e));
                } finally {
                    closeQuietly(folder);
                }
            }

            lastSuccessAt = Instant.now();
            lastError = null;
            settingsService.recordScan(lastSuccessAt);

            ScanReport report = new ScanReport(read, recognised, stored, created, alerted,
                    folders, aiClassifier.callsUsed(), filtered,
                    System.currentTimeMillis() - startedAt, from, null);
            lastReport = report;
            if (stored > 0) {
                log.info("Mailbox scan stored {} event(s) across {} new application(s)", stored, created);
            }
            return report;

        } catch (Exception e) {
            lastError = rootMessage(e);
            log.warn("Mailbox scan failed: {}", lastError);
            alertService.raiseScanError(lastError);
            ScanReport report = new ScanReport(read, recognised, stored, created, alerted,
                    folders, aiClassifier.callsUsed(), filtered,
                    System.currentTimeMillis() - startedAt, from, lastError);
            lastReport = report;
            return report;
        } finally {
            connector.closeQuietly(store);
            running.set(false);
        }
    }

    private record Ingested(boolean recognised, boolean stored, boolean created,
                            boolean alerted, boolean filteredAsPromotional) {
        static final Ingested NOTHING = new Ingested(false, false, false, false, false);
        static final Ingested PROMOTIONAL = new Ingested(false, false, false, false, true);
    }

    private Ingested handle(Message message, AppSettings settings, boolean aiAvailable) {
        try {
            ScannedMessage scanned = reader.read(message);
            Classification classification = classifier.classify(scanned);
            boolean promotional = classifier.looksPromotional(scanned);

            ScanTriage triage = ScanTriage.of(classification,
                    classifier.mightBeJobMail(scanned), promotional, aiAvailable);

            if (triage == ScanTriage.IGNORE) {
                // The rules threw it out. Count it as filtered advertising only
                // when it actually looked like advertising, so the figure means
                // something rather than counting every unrelated email.
                return promotional ? Ingested.PROMOTIONAL : Ingested.NOTHING;
            }
            if (triage.needsAi()) {
                Classification adjudicated =
                        aiClassifier.adjudicate(scanned, classification, settings);
                if (adjudicated == null && classification != null) {
                    // The model overruled a rule match — the false positive this
                    // whole path exists to catch.
                    return Ingested.PROMOTIONAL;
                }
                classification = adjudicated;
            }

            if (classification == null) {
                return promotional ? Ingested.PROMOTIONAL : Ingested.NOTHING;
            }
            ApplicationIngestService.Outcome outcome = ingestService.ingest(scanned, classification);
            return new Ingested(true, outcome.stored(), outcome.newApplication(),
                    outcome.alerted(), false);
        } catch (Exception e) {
            log.debug("Skipped an unreadable message: {}", e.toString());
            return Ingested.NOTHING;
        }
    }

    private void closeQuietly(Folder folder) {
        if (folder == null) {
            return;
        }
        try {
            if (folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception ignored) {
            // nothing useful to do here
        }
    }

    /** Unwraps nested causes so the UI shows the real reason, not "MessagingException". */
    public static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    // ---- status for the UI -------------------------------------------------

    public boolean isRunning() {
        return running.get();
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastError() {
        return lastError;
    }

    public ScanReport getLastReport() {
        return lastReport;
    }

    /** Folder names available on the account, so Settings can offer real choices. */
    public List<String> listFolders() throws Exception {
        AppSettings s = settingsService.get();
        Store store = null;
        try {
            store = connector.connect(s);
            List<String> names = new ArrayList<>();
            collectFolders(store.getDefaultFolder(), names, 0);
            return names;
        } finally {
            connector.closeQuietly(store);
        }
    }

    private void collectFolders(Folder folder, List<String> names, int depth) throws Exception {
        if (depth > 4 || names.size() > 200) {
            return;
        }
        for (Folder child : folder.list()) {
            if ((child.getType() & Folder.HOLDS_MESSAGES) != 0) {
                names.add(child.getFullName());
            }
            if ((child.getType() & Folder.HOLDS_FOLDERS) != 0) {
                collectFolders(child, names, depth + 1);
            }
        }
    }
}
