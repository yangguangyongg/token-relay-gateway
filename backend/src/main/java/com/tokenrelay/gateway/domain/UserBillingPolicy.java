package com.tokenrelay.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_billing_policies")
public record UserBillingPolicy(
    @Id UUID userId,
    String currency,
    BigDecimal monthlyBudgetUsd,
    BigDecimal alertThresholdPercent,
    boolean autoDisableApiKeys,
    String webhookUrl,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
