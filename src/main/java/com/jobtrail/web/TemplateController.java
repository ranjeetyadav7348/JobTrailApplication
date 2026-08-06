package com.jobtrail.web;

import com.jobtrail.service.TemplateRenderer;
import com.jobtrail.service.TemplateService;
import com.jobtrail.web.dto.TemplateForm;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService service;

    @GetMapping
    public List<Views.TemplateView> list() {
        return service.list();
    }

    @GetMapping("/tokens")
    public List<String> tokens() {
        return List.of(TemplateRenderer.SUPPORTED_TOKENS);
    }

    @PostMapping
    public Views.TemplateView create(@Valid @RequestBody TemplateForm form) {
        return service.create(form);
    }

    @PutMapping("/{id}")
    public Views.TemplateView update(@PathVariable Long id, @Valid @RequestBody TemplateForm form) {
        return service.update(id, form);
    }

    @PostMapping("/{id}/default")
    public Views.TemplateView setDefault(@PathVariable Long id) {
        return service.setDefault(id);
    }

    @DeleteMapping("/{id}")
    public Views.ActionResult delete(@PathVariable Long id) {
        service.delete(id);
        return new Views.ActionResult(true, "Template deleted.");
    }
}
