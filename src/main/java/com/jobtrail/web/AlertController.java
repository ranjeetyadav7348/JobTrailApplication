package com.jobtrail.web;

import com.jobtrail.service.AlertService;
import com.jobtrail.service.SettingsService;
import com.jobtrail.web.dto.AppViews;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Feeds the alert centre and the popup. The browser polls {@code /api/alerts}
 * on a short interval, so this stays deliberately cheap.
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;
    private final SettingsService settingsService;

    /** Unread alerts plus the switch that says whether popups are wanted at all. */
    public record AlertFeed(List<AppViews.AlertView> alerts, long unread, boolean popupsEnabled) {
    }

    @GetMapping
    public AlertFeed feed(@RequestParam(required = false, defaultValue = "false") boolean all) {
        List<AppViews.AlertView> alerts = (all ? alertService.recent() : alertService.unread())
                .stream()
                .map(AppViews::of)
                .toList();
        return new AlertFeed(alerts, alertService.unreadCount(),
                settingsService.get().isAlertPopups());
    }

    @PostMapping("/{id}/ack")
    public Views.ActionResult acknowledge(@PathVariable Long id) {
        boolean changed = alertService.acknowledge(id);
        return new Views.ActionResult(changed, changed ? "Dismissed." : "Already dismissed.");
    }

    @PostMapping("/ack-all")
    public Views.ActionResult acknowledgeAll() {
        int count = alertService.acknowledgeAll();
        return new Views.ActionResult(true, count == 0
                ? "Nothing to dismiss."
                : "Dismissed " + count + " alert" + (count == 1 ? "." : "s."));
    }
}
