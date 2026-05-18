package com.tokenrelay.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("monthly_bills")
public record MonthlyBill(
    @Id UUID id,
    LocalDate billMonth,
    UUID userId,
    String currency,
    String status,
    long totalRequests,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    BigDecimal totalCostUsd,
    String note,
    Instant sentAt,
    Instant paidAt,
    Instant createdAt,
    Instant updatedAt) {}
