package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("workspaces")
public record Workspace(
    @Id UUID id,
    String name,
    String slug,
    String type,
    String status,
    UUID createdByUserId,
    Instant createdAt,
    Instant updatedAt) {}
