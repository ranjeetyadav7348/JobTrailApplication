package com.jobtrail.web;

import com.jobtrail.service.ApplicationMaintenanceService;
import com.jobtrail.service.ApplicationStatsService;
import com.jobtrail.service.scan.MailboxScanner;
import com.jobtrail.web.dto.AppViews;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Manual control over the mailbox scan, plus the folder picker in Settings. */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final MailboxScanner scanner;
    private final ApplicationStatsService statsService;
    private final ApplicationMaintenanceService maintenance;

    public record ScanRequest(Integer days) {
    }

    @GetMapping
    public AppViews.ScanStatusView status() {
        return statsService.scanStatus();
    }

    /**
     * Reads the mailbox now. Runs inline rather than in the background because
     * the user is looking at a spinner and wants the numbers, and because two
     * concurrent scans would fight over the same folders.
     */
    @PostMapping
    public AppViews.ScanResultView scan(@RequestBody(required = false) ScanRequest body) {
        Integer days = body == null ? null : body.days();
        MailboxScanner.ScanReport report;
        try {
            report = scanner.scanNow(days);
        } catch (IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        // Newly discovered deadlines should be visible without waiting an hour
        // for the scheduled sweep.
        maintenance.warnAboutDeadlines();

        return new AppViews.ScanResultView(
                report.ok(),
                describe(report),
                report.messagesRead(), report.recognised(), report.stored(),
                report.newApplications(), report.alertsRaised(), report.foldersRead(),
                report.aiCalls(), report.promotionalFiltered(),
                report.durationMs(), report.from());
    }

    @GetMapping("/folders")
    public List<String> folders() {
        try {
            return scanner.listFolders();
        } catch (Exception e) {
            throw ApiException.badRequest(MailboxScanner.rootMessage(e));
        }
    }

    private String describe(MailboxScanner.ScanReport r) {
        if (!r.ok()) {
            return "Scan failed: " + r.error();
        }
        if (r.messagesRead() == 0) {
            return "No mail in that window.";
        }
        if (r.stored() == 0) {
            return "Read " + r.messagesRead() + " message" + plural(r.messagesRead())
                    + " — nothing new to track.";
        }
        return "Read " + r.messagesRead() + " message" + plural(r.messagesRead())
                + ", recorded " + r.stored() + " update" + plural(r.stored())
                + " and found " + r.newApplications() + " new application"
                + plural(r.newApplications()) + "."
                + (r.promotionalFiltered() > 0
                    ? " Ignored " + r.promotionalFiltered() + " advert"
                      + plural(r.promotionalFiltered()) + "." : "")
                + (r.aiCalls() > 0 ? " Asked the model about " + r.aiCalls() + "." : "");
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }
}
