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
    String monthKey = YearMonth.now(ZoneOffset.UTC).toString();
    String apiKeyQuotaKey = "quota:key:" + context.apiKey().id() + ":" + monthKey;
    String userQuotaKey = "quota:user:" + context.user().id() + ":" + monthKey;

    Mono<Void> reserveApiKeyQuota = reserveCounter(
        apiKeyQuotaKey,
        context.apiKey().monthlyTokenQuota(),
        "api_key_quota_exceeded",
        "API key monthly token quota exceeded");

    Mono<Void> reserveUserQuota = reserveCounter(
        userQuotaKey,
        context.user().monthlyTokenQuota(),
        "quota_exceeded",
        "Monthly token quota exceeded");

    return reserveApiKeyQuota
        .then(reserveUserQuota.onErrorResume(error -> rollback(apiKeyQuotaKey).then(Mono.error(error))));
  }

  private Mono<Void> reserveCounter(String key, Long limit, String errorCode, String message) {
    if (limit == null || limit <= 0) {
      return Mono.empty();
    }
    return redis.opsForValue().increment(key, DEFAULT_RESERVATION_TOKENS)
        .flatMap(total -> redis.expire(key, Duration.ofDays(45)).thenReturn(total))
        .flatMap(total -> {
          if (total > limit) {
            return rollback(key)
                .then(Mono.error(new GatewayException(402, errorCode, message)));
          }
          return Mono.empty();
        });
  }

  private Mono<Void> rollback(String key) {
    return redis.opsForValue().increment(key, -DEFAULT_RESERVATION_TOKENS).then();
  }
}
