package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("workspace_memberships")
public record WorkspaceMembership(
    @Id UUID id,
    UUID workspaceId,
    UUID userId,
    String role,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
