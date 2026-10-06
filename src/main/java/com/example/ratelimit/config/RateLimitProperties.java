package com.example.ratelimit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised rate-limit settings, bound from application.yml (prefix "rate-limit").
 *
 * <pre>
 * rate-limit:
 *   enabled: true
 *   capacity: 5                 # max burst size (bucket capacity, in tokens)
 *   refill-tokens-per-second: 1 # sustained rate (tokens added per second)
 * </pre>
 */
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /** Whether rate limiting is active. */
    private boolean enabled = true;

    /** Maximum number of tokens the bucket can hold (allowed burst size). */
    private long capacity = 5;

    /** Continuous refill rate, in tokens per second. */
    private double refillTokensPerSecond = 1.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public double getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    public void setRefillTokensPerSecond(double refillTokensPerSecond) {
        this.refillTokensPerSecond = refillTokensPerSecond;
    }
}
