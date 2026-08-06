package com.jobtrail.web;

import com.jobtrail.service.OutreachService;
import com.jobtrail.web.dto.BulkOutreachForm;
import com.jobtrail.web.dto.OutreachForm;
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

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/outreach")
@RequiredArgsConstructor
public class OutreachController {

    private final OutreachService service;

    public record QueueRequest(Long templateId, String subjectOverride, String bodyOverride,
                               Instant scheduledAt) {
    }

    public record FollowUpRequest(Long templateId, Boolean ignoreCap) {
    }

    public record StatusRequest(String status) {
    }

    @GetMapping
    public List<Views.OutreachView> list(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status) {
        return service.list(q, status);
    }

    @GetMapping("/{id}")
    public Views.OutreachDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    public Views.OutreachView create(@Valid @RequestBody OutreachForm form) {
        return service.create(form);
    }

    @PostMapping("/bulk")
    public OutreachService.BulkResult createBulk(@Valid @RequestBody BulkOutreachForm form) {
        return service.createBulk(form);
    }

    @PutMapping("/{id}")
    public Views.OutreachView update(@PathVariable Long id, @Valid @RequestBody OutreachForm form) {
        return service.update(id, form);
    }

    @DeleteMapping("/{id}")
    public Views.ActionResult delete(@PathVariable Long id) {
        service.delete(id);
        return new Views.ActionResult(true, "Thread deleted.");
    }

    /** Puts the opening email into the paced send queue. */
    @PostMapping("/{id}/queue")
    public Views.MessageView queue(@PathVariable Long id,
                                   @RequestBody(required = false) QueueRequest body) {
        QueueRequest r = body == null ? new QueueRequest(null, null, null, null) : body;
        return service.queueInitial(id, r.templateId(), r.subjectOverride(), r.bodyOverride(),
                r.scheduledAt());
    }

    @PostMapping("/{id}/follow-up")
    public Views.MessageView followUp(@PathVariable Long id,
                                      @RequestBody(required = false) FollowUpRequest body) {
        FollowUpRequest r = body == null ? new FollowUpRequest(null, null) : body;
        return service.queueFollowUp(id, r.templateId(), Boolean.TRUE.equals(r.ignoreCap()));
    }

    @PostMapping("/{id}/status")
    public Views.OutreachView setStatus(@PathVariable Long id, @RequestBody StatusRequest body) {
        return service.setStatus(id, body.status());
    }

    @GetMapping("/{id}/preview")
    public Views.PreviewView preview(@PathVariable Long id,
                                     @RequestParam(required = false) Long templateId,
                                     @RequestParam(required = false, defaultValue = "INITIAL") String kind,
                                     @RequestParam(required = false) String subjectOverride,
                                     @RequestParam(required = false) String bodyOverride) {
        return service.preview(id, templateId, kind, subjectOverride, bodyOverride);
    }
}
