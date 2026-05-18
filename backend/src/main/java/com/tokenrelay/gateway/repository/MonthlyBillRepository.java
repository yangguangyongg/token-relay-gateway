package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.MonthlyBill;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MonthlyBillRepository extends ReactiveCrudRepository<MonthlyBill, UUID> {
  Flux<MonthlyBill> findByBillMonthOrderByTotalCostUsdDesc(LocalDate billMonth);
  Mono<MonthlyBill> findByBillMonthAndUserId(LocalDate billMonth, UUID userId);
}
