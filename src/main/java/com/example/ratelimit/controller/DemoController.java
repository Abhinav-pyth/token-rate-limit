package com.example.ratelimit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * A simple protected endpoint to demo rate limiting.
 * Hit it more than {capacity} times in a burst and you will receive 429 responses.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of(
                "message", "Hello! This request was allowed by the token bucket.",
                "timestamp", Instant.now().toString()
        );
    }
}
