package com.example.ratelimit.bucket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry that keeps one {@link TokenBucket} per client key (e.g. per client IP address).
 *
 * <p>Buckets are created lazily on first use and stored in a {@link ConcurrentHashMap},
 * so the registry itself is thread-safe without global locking.</p>
 */
public class TokenBucketRegistry {

    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    private final long capacity;
    private final double refillTokensPerSecond;

    public TokenBucketRegistry(long capacity, double refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
    }

    /** Return the bucket for the given key, creating it if necessary. */
    public TokenBucket bucketFor(String key) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillTokensPerSecond));
    }

    /** Convenience: try to consume a token from the bucket identified by {@code key}. */
    public boolean tryConsume(String key) {
        return bucketFor(key).tryConsume();
    }

    /** Exposed for testing/monitoring. */
    public int trackedClients() {
        return buckets.size();
    }
}
