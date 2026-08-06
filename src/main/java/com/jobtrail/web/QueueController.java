package com.jobtrail.web;

import com.jobtrail.service.OutreachService;
import com.jobtrail.service.SendQueueService;
import com.jobtrail.service.SettingsService;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueueController {

    private final SendQueueService queueService;
    private final OutreachService outreachService;
    private final SettingsService settingsService;

    public record PauseRequest(Boolean paused) {
    }

    @GetMapping("/queue")
    public Views.QueueView queue() {
        return queueService.queueView();
    }

    @PostMapping("/queue/pause")
    public Views.ActionResult pause(@RequestBody PauseRequest body) {
        boolean paused = Boolean.TRUE.equals(body.paused());
        settingsService.setPaused(paused);
        return new Views.ActionResult(true, paused ? "Sending paused." : "Sending resumed.");
    }

    @GetMapping("/messages/recent")
    public List<Views.MessageView> recent(@RequestParam(defaultValue = "25") int limit) {
        return queueService.recent(limit);
    }

    @PostMapping("/messages/{id}/cancel")
    public Views.ActionResult cancel(@PathVariable Long id) {
        outreachService.cancelMessage(id);
        return new Views.ActionResult(true, "Email removed from the queue.");
    }

    @PostMapping("/messages/{id}/retry")
    public Views.MessageView retry(@PathVariable Long id) {
        return outreachService.retryMessage(id);
    }
}
