package com.example.ratelimit.bucket;

/**
 * A thread-safe, lazy-refill Token Bucket implementation.
 *
 * <p>The bucket holds at most {@code capacity} tokens and refills continuously at a
 * fixed rate of {@code refillTokensPerSecond}. Instead of using a background scheduler,
 * tokens are "virtually" added on every access based on the elapsed time since the
 * last refill — this keeps the implementation simple and allocation-free.</p>
 *
 * <pre>
 *   Example: capacity = 5, refill = 1 token/sec
 *
 *   t=0s : burst of 5 requests allowed (bucket drained: 5 -> 0)
 *   t=0..1s : further requests rejected (429 Too Many Requests)
 *   t=1s : 1 token available again, one more request passes
 * </pre>
 */
public class TokenBucket {

    private final long capacity;
    private final double refillTokensPerSecond;

    /** Current number of tokens (can be fractional internally, but consumed as whole tokens). */
    private double availableTokens;

    /** Timestamp of the last refill, in nanoseconds (monotonic clock). */
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillTokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be > 0");
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.availableTokens = capacity; // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Try to consume one token from the bucket.
     *
     * @return {@code true} if the request is allowed, {@code false} if it should be rate-limited.
     */
    public synchronized boolean tryConsume() {
        return tryConsume(1);
    }

    /**
     * Try to consume {@code tokens} tokens from the bucket.
     *
     * @param tokens number of tokens required for this operation
     * @return {@code true} if enough tokens were available and have been consumed
     */
    public synchronized boolean tryConsume(int tokens) {
        refill();
        if (availableTokens >= tokens) {
            availableTokens -= tokens;
            return true;
        }
        return false;
    }

    /**
     * @return an estimate of how many milliseconds until at least one token is available
     *         (0 if tokens are already available).
     */
    public synchronized long millisUntilNextToken() {
        refill();
        if (availableTokens >= 1) {
            return 0;
        }
        double missing = 1 - availableTokens;
        return Math.round((missing / refillTokensPerSecond) * 1000.0);
    }

    /** Snapshot of currently available tokens (whole tokens), useful for headers/metrics. */
    public synchronized int remainingTokens() {
        refill();
        return (int) Math.floor(availableTokens);
    }

    public long getCapacity() {
        return capacity;
    }

    public double getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    /** Refill tokens based on elapsed time since the previous refill. Caller must hold the lock. */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            double newTokens = elapsedSeconds * refillTokensPerSecond;
            availableTokens = Math.min(capacity, availableTokens + newTokens);
            lastRefillNanos = now;
        }
    }
}
