package com.tokenrelay.gateway.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("audit_logs")
public record AuditLog(
    @Id UUID id,
    String actor,
    String action,
    String target,
    String details,
    Instant createdAt) {}
