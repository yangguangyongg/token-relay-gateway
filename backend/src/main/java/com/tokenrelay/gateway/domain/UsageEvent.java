package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("usage_events")
public record UsageEvent(
    @Id UUID id,
    UUID userId,
    UUID apiKeyId,
    String provider,
    String model,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long billablePromptTokens,
    long billableCompletionTokens,
    BigDecimal estimatedCostUsd,
    UUID pricingRuleId,
    String billingStatus,
    Instant billedAt,
    int statusCode,
    String requestId,
    Instant createdAt) {}
