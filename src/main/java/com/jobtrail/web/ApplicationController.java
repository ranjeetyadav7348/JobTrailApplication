package com.jobtrail.web;

import com.jobtrail.domain.ApplicationStatus;
import com.jobtrail.domain.JobPlatform;
import com.jobtrail.service.ApplicationService;
import com.jobtrail.service.ApplicationStatsService;
import com.jobtrail.web.dto.AppViews;
import com.jobtrail.web.dto.ApplicationForm;
import com.jobtrail.web.dto.Views;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService service;
    private final ApplicationStatsService statsService;

    public record StatusRequest(String status) {
    }

    public record ArchiveRequest(Boolean archived) {
    }

    /** Enum values the UI needs to build its filters and forms. */
    public record OptionsView(List<Option> statuses, List<Option> platforms) {
    }

    public record Option(String value, String label, String colour) {
    }

    @GetMapping
    public List<AppViews.ApplicationView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Boolean includeArchived) {
        return service.list(q, status, platform, includeArchived);
    }

    @GetMapping("/stats")
    public AppViews.ApplicationStatsView stats() {
        return statsService.dashboard();
    }

    @GetMapping("/options")
    public OptionsView options() {
        return new OptionsView(
                Arrays.stream(ApplicationStatus.values())
                        .map(s -> new Option(s.name(), s.label(), s.cssVar()))
                        .toList(),
                Arrays.stream(JobPlatform.values())
                        .map(p -> new Option(p.name(), p.label(), p.colour()))
                        .toList());
    }

    @GetMapping("/{id}")
    public AppViews.ApplicationDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    public AppViews.ApplicationView create(@Valid @RequestBody ApplicationForm form) {
        return service.create(form);
    }

    @PutMapping("/{id}")
    public AppViews.ApplicationView update(@PathVariable Long id,
                                           @RequestBody ApplicationForm form) {
        return service.update(id, form);
    }

    @PostMapping("/{id}/status")
    public AppViews.ApplicationView setStatus(@PathVariable Long id,
                                              @RequestBody StatusRequest body) {
        return service.setStatus(id, body.status());
    }

    @PostMapping("/{id}/archive")
    public AppViews.ApplicationView archive(@PathVariable Long id,
                                            @RequestBody(required = false) ArchiveRequest body) {
        boolean archived = body == null || body.archived() == null || body.archived();
        return service.setArchived(id, archived);
    }

    @DeleteMapping("/{id}")
    public Views.ActionResult delete(@PathVariable Long id) {
        service.delete(id);
        return new Views.ActionResult(true, "Application removed.");
    }
}
