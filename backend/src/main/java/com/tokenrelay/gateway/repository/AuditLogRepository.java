package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.AuditLog;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, UUID> {}
