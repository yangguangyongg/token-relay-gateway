package com.tokenrelay.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("model_pricing")
public record ModelPricing(
    @Id UUID id,
    String provider,
    String modelPattern,
    String currency,
    @Column("prompt_price_per_1m_tokens")
    BigDecimal promptPricePer1mTokens,
    @Column("completion_price_per_1m_tokens")
    BigDecimal completionPricePer1mTokens,
    String status,
    Instant effectiveFrom,
    Instant createdAt) {}
