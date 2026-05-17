package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.AuditLog;
import com.tokenrelay.gateway.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AuditService {
  private final AuditLogRepository audits;

  public AuditService(AuditLogRepository audits) {
    this.audits = audits;
  }

  public Mono<Void> log(String actor, String action, String target, String details) {
    return audits.save(new AuditLog(null, actor, action, target, details, null)).then();
  }
}
