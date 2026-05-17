package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.ApiKeyRecord;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RateLimitService {
  private final ReactiveStringRedisTemplate redis;

  public RateLimitService(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  public Mono<Void> check(ApiKeyRecord apiKey) {
    long minute = Instant.now().getEpochSecond() / 60;
    String key = "rl:" + apiKey.id() + ":" + minute;
    return redis.opsForValue().increment(key)
        .flatMap(count -> redis.expire(key, Duration.ofMinutes(2)).thenReturn(count))
        .flatMap(count -> {
          if (count > apiKey.rateLimitPerMinute()) {
            return Mono.error(new GatewayException(429, "rate_limit_exceeded", "Rate limit exceeded"));
          }
          return Mono.empty();
        });
  }
}
