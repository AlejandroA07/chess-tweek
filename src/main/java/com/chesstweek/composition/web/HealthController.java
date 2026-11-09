package com.chesstweek.composition.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public final class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    public record HealthResponse(String status) {}
}
