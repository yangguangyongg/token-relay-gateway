package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.domain.UserBillingPolicy;
import com.tokenrelay.gateway.repository.ApiKeyRepository;
import com.tokenrelay.gateway.repository.UserBillingPolicyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class BillingControlService {
  private final UserBillingPolicyRepository billingPolicies;
  private final ApiKeyRepository apiKeyRepository;
  private final DatabaseClient databaseClient;
  private final AuditService auditService;
  private final WebClient webClient;

  public BillingControlService(
      UserBillingPolicyRepository billingPolicies,
      ApiKeyRepository apiKeyRepository,
      DatabaseClient databaseClient,
      AuditService auditService,
      WebClient webClient) {
    this.billingPolicies = billingPolicies;
    this.apiKeyRepository = apiKeyRepository;
    this.databaseClient = databaseClient;
    this.auditService = auditService;
    this.webClient = webClient;
  }

  public Mono<Void> evaluate(UUID userId, BigDecimal latestCostUsd, String requestId) {
    if (userId == null || latestCostUsd == null) {
      return Mono.empty();
    }

    YearMonth month = YearMonth.now(ZoneOffset.UTC);
    Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return billingPolicies.findById(userId)
        .filter(policy -> "ACTIVE".equalsIgnoreCase(policy.status()))
        .filter(policy -> policy.monthlyBudgetUsd() != null && policy.monthlyBudgetUsd().compareTo(BigDecimal.ZERO) > 0)
        .flatMap(policy -> monthlySpend(userId, start, end)
            .flatMap(spend -> applyPolicy(policy, spend, latestCostUsd, requestId, month)))
        .onErrorResume(ex -> auditService.log("system", "BILLING_CONTROL_ERROR", userId.toString(), ex.getMessage()))
        .then();
  }

  private Mono<BigDecimal> monthlySpend(UUID userId, Instant start, Instant end) {
    return databaseClient.sql("""
        SELECT coalesce(sum(estimated_cost_usd), 0) AS spend
        FROM usage_events
        WHERE user_id = :user_id
          AND created_at >= :start_time
          AND created_at < :end_time
        """)
        .bind("user_id", userId)
        .bind("start_time", start)
        .bind("end_time", end)
        .fetch()
        .one()
        .map(row -> toDecimal(row.get("spend")))
        .defaultIfEmpty(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP));
  }

  private Mono<Void> applyPolicy(
      UserBillingPolicy policy,
      BigDecimal currentSpend,
      BigDecimal latestCostUsd,
      String requestId,
      YearMonth month) {
    BigDecimal budget = policy.monthlyBudgetUsd().setScale(8, RoundingMode.HALF_UP);
    BigDecimal previousSpend = currentSpend.subtract(latestCostUsd).max(BigDecimal.ZERO);
    BigDecimal threshold = budget.multiply(policy.alertThresholdPercent())
        .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

    boolean crossedThreshold = previousSpend.compareTo(threshold) < 0 && currentSpend.compareTo(threshold) >= 0;
    boolean crossedBudget = previousSpend.compareTo(budget) < 0 && currentSpend.compareTo(budget) >= 0;

    Mono<Void> alert = Mono.empty();
    if (crossedThreshold) {
      String details = "month=" + month + ", spend=" + currentSpend + ", threshold=" + threshold + ", request_id=" + requestId;
      alert = auditService.log("billing", "BUDGET_THRESHOLD_REACHED", policy.userId().toString(), details)
          .then(triggerWebhook(policy, "budget_threshold_reached", month, currentSpend, budget, threshold, requestId));
    }

    Mono<Void> enforcement = Mono.empty();
    if (crossedBudget && policy.autoDisableApiKeys()) {
      enforcement = disableUserApiKeys(policy.userId())
          .then(auditService.log(
              "billing",
              "BUDGET_EXCEEDED_KEYS_DISABLED",
              policy.userId().toString(),
              "month=" + month + ", spend=" + currentSpend + ", budget=" + budget + ", request_id=" + requestId))
          .then(triggerWebhook(policy, "budget_exceeded_keys_disabled", month, currentSpend, budget, threshold, requestId));
    }

    return alert.then(enforcement);
  }

  private Mono<Void> disableUserApiKeys(UUID userId) {
    return apiKeyRepository.findByUserId(userId)
        .filter(key -> "ACTIVE".equalsIgnoreCase(key.status()))
        .flatMap(key -> apiKeyRepository.save(withStatus(key, "DISABLED")))
        .then();
  }

  private Mono<Void> triggerWebhook(
      UserBillingPolicy policy,
      String event,
      YearMonth month,
      BigDecimal spend,
      BigDecimal budget,
      BigDecimal threshold,
      String requestId) {
    if (policy.webhookUrl() == null || policy.webhookUrl().isBlank()) {
      return Mono.empty();
    }

    return webClient.post()
        .uri(policy.webhookUrl())
        .bodyValue(Map.of(
            "event", event,
            "userId", policy.userId().toString(),
            "month", month.toString(),
            "currency", policy.currency(),
            "spendUsd", spend,
            "budgetUsd", budget,
            "thresholdUsd", threshold,
            "requestId", requestId,
            "timestamp", Instant.now().toString()))
        .retrieve()
        .toBodilessEntity()
        .then()
        .onErrorResume(ex -> auditService.log(
            "billing",
            "BILLING_WEBHOOK_FAILED",
            policy.userId().toString(),
            "event=" + event + ", error=" + ex.getMessage()).then());
  }

  private BigDecimal toDecimal(Object value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.setScale(8, RoundingMode.HALF_UP);
    }
    return new BigDecimal(value.toString()).setScale(8, RoundingMode.HALF_UP);
  }

  private ApiKeyRecord withStatus(ApiKeyRecord key, String status) {
    return new ApiKeyRecord(
        key.id(),
        key.userId(),
        key.name(),
        key.keyPrefix(),
        key.keyHash(),
        status,
        key.rateLimitPerMinute(),
        key.createdAt(),
        key.lastUsedAt());
  }
}
