package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("gateway_users")
public record GatewayUser(
    @Id UUID id,
    String email,
    String displayName,
    String status,
    long monthlyTokenQuota,
    String passwordHash,
    Instant createdAt,
    Instant updatedAt) {}
