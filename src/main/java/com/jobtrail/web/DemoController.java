package com.jobtrail.web;

import com.jobtrail.service.DemoDataService;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoDataService demoDataService;

    public record DemoState(boolean loaded) {
    }

    @GetMapping
    public DemoState state() {
        return new DemoState(demoDataService.isLoaded());
    }

    @PostMapping("/seed")
    public Views.ActionResult seed() {
        int created = demoDataService.seed();
        return new Views.ActionResult(true, created + " sample threads added.");
    }

    @DeleteMapping
    public Views.ActionResult remove() {
        int removed = demoDataService.remove();
        return new Views.ActionResult(true, removed == 0
                ? "There was no demo data to remove."
                : removed + " sample threads removed.");
    }
}
