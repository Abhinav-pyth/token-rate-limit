package com.example.ratelimit.config;

import com.example.ratelimit.bucket.TokenBucketRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public TokenBucketRegistry tokenBucketRegistry(RateLimitProperties properties) {
        return new TokenBucketRegistry(properties.getCapacity(), properties.getRefillTokensPerSecond());
    }
}
