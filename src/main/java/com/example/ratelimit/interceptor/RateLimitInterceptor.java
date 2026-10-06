package com.example.ratelimit.interceptor;

import com.example.ratelimit.bucket.TokenBucket;
import com.example.ratelimit.bucket.TokenBucketRegistry;
import com.example.ratelimit.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP interceptor that applies per-client token-bucket rate limiting.
 *
 * <p>Each client (identified by IP address, honouring {@code X-Forwarded-For}) gets its own
 * bucket. On every request one token is consumed; if the bucket is empty the request is
 * rejected with {@code 429 Too Many Requests}, a {@code Retry-After} header and a JSON body.</p>
 *
 * <p>Standard rate-limit headers are always returned:</p>
 * <ul>
 *   <li>{@code X-RateLimit-Limit}: bucket capacity (max burst)</li>
 *   <li>{@code X-RateLimit-Remaining}: tokens left after this request</li>
 *   <li>{@code X-RateLimit-Reset}: seconds until the next token is available</li>
 * </ul>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketRegistry registry;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitInterceptor(TokenBucketRegistry registry, RateLimitProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!properties.isEnabled()) {
            return true; // rate limiting disabled via configuration
        }

        String clientKey = resolveClientKey(request);
        TokenBucket bucket = registry.bucketFor(clientKey);

        if (bucket.tryConsume()) {
            addRateLimitHeaders(response, bucket);
            return true;
        }

        // Bucket empty -> reject with 429
        long retryAfterMillis = bucket.millisUntilNextToken();
        long retryAfterSeconds = Math.max(1, (long) Math.ceil(retryAfterMillis / 1000.0));

        addRateLimitHeaders(response, bucket);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Map<String, Object> body = Map.of(
                "error", "Too Many Requests",
                "message", "Rate limit exceeded. Try again in ~" + retryAfterSeconds + " second(s).",
                "retryAfterSeconds", retryAfterSeconds,
                "limit", bucket.getCapacity(),
                "refillTokensPerSecond", bucket.getRefillTokensPerSecond()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    private void addRateLimitHeaders(HttpServletResponse response, TokenBucket bucket) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(bucket.getCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, bucket.remainingTokens())));
        response.setHeader("X-RateLimit-Reset", String.valueOf(Math.ceil(bucket.millisUntilNextToken() / 1000.0)));
    }

    /**
     * Identify the client. Prefers the first entry of {@code X-Forwarded-For} when the app
     * runs behind a proxy; falls back to the remote address.
     */
    private String resolveClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
