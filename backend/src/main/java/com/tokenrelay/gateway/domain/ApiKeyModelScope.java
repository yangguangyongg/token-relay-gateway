package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("api_key_model_scopes")
public record ApiKeyModelScope(
    @Id UUID id,
    UUID apiKeyId,
    String modelPattern,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
