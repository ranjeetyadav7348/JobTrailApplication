package com.jobtrail.web;

import com.jobtrail.service.DiagnosticsService;
import com.jobtrail.service.SettingsService;
import com.jobtrail.web.dto.SettingsForm;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final DiagnosticsService diagnostics;

    public record TestEmailRequest(String to) {
    }

    @GetMapping
    public Views.SettingsView get() {
        return Views.of(settingsService.get(), settingsService.intervalFloorSeconds());
    }

    @PutMapping
    public Views.SettingsView update(@RequestBody SettingsForm form) {
        return Views.of(settingsService.update(form), settingsService.intervalFloorSeconds());
    }

    @PostMapping("/test-smtp")
    public Views.ActionResult testSmtp() {
        return diagnostics.testSmtp();
    }

    @PostMapping("/test-email")
    public Views.ActionResult testEmail(@RequestBody TestEmailRequest body) {
        return diagnostics.sendTestEmail(body.to());
    }

    @PostMapping("/test-imap")
    public Views.ActionResult testImap() {
        return diagnostics.testImap();
    }
}
