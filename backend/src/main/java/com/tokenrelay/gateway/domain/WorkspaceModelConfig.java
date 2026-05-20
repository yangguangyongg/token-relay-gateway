package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("workspace_model_configs")
public record WorkspaceModelConfig(
    @Id UUID id,
    UUID workspaceId,
    String provider,
    String modelPattern,
    boolean enabled,
    Integer maxTokens,
    String status,
    UUID createdByUserId,
    Instant createdAt,
    Instant updatedAt) {}
