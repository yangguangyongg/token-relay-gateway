package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.auth.AuthContext;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class QuotaService {
  private static final long DEFAULT_RESERVATION_TOKENS = 4096;
  private final ReactiveStringRedisTemplate redis;

  public QuotaService(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  public Mono<Void> reserve(AuthContext context) {
    String key = "quota:" + context.user().id() + ":" + YearMonth.now(ZoneOffset.UTC);
    return redis.opsForValue().increment(key, DEFAULT_RESERVATION_TOKENS)
        .flatMap(total -> redis.expire(key, Duration.ofDays(45)).thenReturn(total))
        .flatMap(total -> {
          if (total > context.user().monthlyTokenQuota()) {
            return Mono.error(new GatewayException(402, "quota_exceeded", "Monthly token quota exceeded"));
          }
          return Mono.empty();
        });
  }
}
