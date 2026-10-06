package com.example.ratelimit;

import com.example.ratelimit.bucket.TokenBucket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the core {@link TokenBucket} algorithm.
 */
class TokenBucketTest {

    @Test
    void shouldAllowBurstUpToCapacityAndThenReject() {
        TokenBucket bucket = new TokenBucket(3, 1.0);

        assertTrue(bucket.tryConsume(), "1st request allowed");
        assertTrue(bucket.tryConsume(), "2nd request allowed");
        assertTrue(bucket.tryConsume(), "3rd request allowed");
        assertFalse(bucket.tryConsume(), "4th request must be rejected (bucket empty)");
        assertEquals(0, bucket.remainingTokens());
    }

    @Test
    void shouldRefillOverTime() throws InterruptedException {
        // capacity 2, refills 10 tokens/sec -> a token every 100ms
        TokenBucket bucket = new TokenBucket(2, 10.0);

        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());

        Thread.sleep(150); // wait long enough for at least one token to be refilled

        assertTrue(bucket.tryConsume(), "request should pass again after refill");
    }

    @Test
    void shouldNotExceedCapacityWhenIdle() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(2, 50.0); // very fast refill
        assertTrue(bucket.tryConsume());

        Thread.sleep(200); // would add ~10 tokens if not capped

        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume(), "refill must be capped at capacity");
    }

    @Test
    void tryConsumeMultipleTokensAtOnce() {
        TokenBucket bucket = new TokenBucket(5, 1.0);
        assertTrue(bucket.tryConsume(4), "consuming 4 of 5 tokens succeeds");
        assertFalse(bucket.tryConsume(2), "only 1 token left, consuming 2 fails");
        assertTrue(bucket.tryConsume(1));
    }

    @Test
    void millisUntilNextTokenIsPositiveWhenEmpty() {
        TokenBucket bucket = new TokenBucket(1, 2.0); // 1 token every 500ms
        assertTrue(bucket.tryConsume());
        long wait = bucket.millisUntilNextToken();
        assertTrue(wait >= 0 && wait <= 500, "expected 0..500ms, got " + wait);
    }

    @Test
    void isThreadSafeUnderConcurrency() throws InterruptedException {
        final int capacity = 100;
        TokenBucket bucket = new TokenBucket(capacity, 0.001); // effectively no refill during test

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (bucket.tryConsume()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(capacity, allowed.get(), "exactly 'capacity' requests may pass without refill");
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrowsIllegalArgument(0, 1.0);
        assertThrowsIllegalArgument(5, 0.0);
        assertThrowsIllegalArgument(5, -1.0);
    }

    private void assertThrowsIllegalArgument(long capacity, double refill) {
        try {
            new TokenBucket(capacity, refill);
            throw new AssertionError("Expected IllegalArgumentException for capacity="
                    + capacity + ", refill=" + refill);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
