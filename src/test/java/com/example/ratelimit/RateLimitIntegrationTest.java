package com.example.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test: the interceptor should allow a burst of {@code capacity} requests
 * for one client and then answer with 429, including rate-limit headers.
 *
 * <p>Each test uses a unique X-Forwarded-For value so buckets don't interfere.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsBurstThenRejectsWithTooManyRequests() throws Exception {
        String client = "10.0.0." + System.nanoTime(); // unique bucket per run

        // application.yml: capacity=5 -> first 5 requests OK
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(get("/api/hello").header("X-Forwarded-For", client))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"))
                    .andExpect(jsonPath("$.message").exists());
        }

        // 6th request must be rate limited
        MvcResult rejected = mockMvc.perform(get("/api/hello").header("X-Forwarded-For", client))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andReturn();

        System.out.println("429 body: " + rejected.getResponse().getContentAsString());
    }

    @Test
    void differentClientsHaveIndependentBuckets() throws Exception {
        String alice = "10.1.1." + System.nanoTime();
        String bob = "10.2.2." + System.nanoTime();

        // Drain Alice's bucket completely
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/api/hello").header("X-Forwarded-For", alice));
        }

        // Bob still has a fresh full bucket
        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", bob))
                .andExpect(status().isOk());
    }

    @Test
    void recoversAfterRefill() throws Exception {
        String client = "10.3.3." + System.nanoTime();

        // capacity=5, refill=1/s -> after draining, wait ~1.2s for one token back
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/hello").header("X-Forwarded-For", client))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", client))
                .andExpect(status().isTooManyRequests());

        Thread.sleep(1200);

        mockMvc.perform(get("/api/hello").header("X-Forwarded-For", client))
                .andExpect(status().isOk());
    }
}
