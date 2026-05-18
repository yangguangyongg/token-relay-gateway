package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.config.AdminSecurityWebFilter;
import com.tokenrelay.gateway.domain.ModelPricing;
import com.tokenrelay.gateway.domain.MonthlyBill;
import com.tokenrelay.gateway.domain.UserBillingPolicy;
import com.tokenrelay.gateway.repository.ModelPricingRepository;
import com.tokenrelay.gateway.repository.MonthlyBillRepository;
import com.tokenrelay.gateway.repository.UserBillingPolicyRepository;
import com.tokenrelay.gateway.service.AuditService;
import com.tokenrelay.gateway.service.GatewayException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin")
public class BillingAdminController {
  private final ModelPricingRepository modelPricingRepository;
  private final MonthlyBillRepository monthlyBillRepository;
  private final UserBillingPolicyRepository billingPolicyRepository;
  private final DatabaseClient databaseClient;
  private final AuditService auditService;

  public BillingAdminController(
      ModelPricingRepository modelPricingRepository,
      MonthlyBillRepository monthlyBillRepository,
      UserBillingPolicyRepository billingPolicyRepository,
      DatabaseClient databaseClient,
      AuditService auditService) {
    this.modelPricingRepository = modelPricingRepository;
    this.monthlyBillRepository = monthlyBillRepository;
    this.billingPolicyRepository = billingPolicyRepository;
    this.databaseClient = databaseClient;
    this.auditService = auditService;
  }

  @GetMapping("/pricing/models")
  public Flux<ModelPricing> pricingModels(ServerWebExchange exchange) {
    return modelPricingRepository.findByStatusOrderByEffectiveFromDesc("ACTIVE");
  }

  @PostMapping("/pricing/models")
  public Mono<ModelPricing> createPricingModel(
      ServerWebExchange exchange,
      @Valid @RequestBody CreatePricingModelRequest request) {
    String actor = currentAdmin(exchange).username();
    ModelPricing pricing = new ModelPricing(
        null,
        request.provider().trim().toUpperCase(Locale.ROOT),
        request.modelPattern().trim(),
        "USD",
        request.promptPricePer1mTokens().setScale(8, RoundingMode.HALF_UP),
        request.completionPricePer1mTokens().setScale(8, RoundingMode.HALF_UP),
        "ACTIVE",
        request.effectiveFrom() == null ? Instant.now() : request.effectiveFrom(),
        Instant.now());
    return modelPricingRepository.save(pricing)
        .flatMap(saved -> auditService.log(actor, "CREATE_MODEL_PRICING", saved.id().toString(), saved.provider() + ":" + saved.modelPattern())
            .thenReturn(saved));
  }

  @GetMapping("/billing/policies")
  public Flux<UserBillingPolicy> billingPolicies(ServerWebExchange exchange) {
    return billingPolicyRepository.findAll();
  }

  @PostMapping("/billing/policies")
  public Mono<UserBillingPolicy> upsertBillingPolicy(
      ServerWebExchange exchange,
      @Valid @RequestBody UpsertBillingPolicyRequest request) {
    String actor = currentAdmin(exchange).username();
    return billingPolicyRepository.findById(request.userId())
        .defaultIfEmpty(new UserBillingPolicy(
            request.userId(),
            "USD",
            BigDecimal.ZERO,
            BigDecimal.valueOf(80),
            false,
            null,
            "ACTIVE",
            Instant.now(),
            Instant.now()))
        .flatMap(existing -> billingPolicyRepository.save(new UserBillingPolicy(
            existing.userId(),
            "USD",
            request.monthlyBudgetUsd().setScale(8, RoundingMode.HALF_UP),
            request.alertThresholdPercent().setScale(2, RoundingMode.HALF_UP),
            request.autoDisableApiKeys(),
            request.webhookUrl(),
            "ACTIVE",
            existing.createdAt() == null ? Instant.now() : existing.createdAt(),
            Instant.now())))
        .flatMap(saved -> auditService.log(actor, "UPSERT_BILLING_POLICY", saved.userId().toString(), "budget=" + saved.monthlyBudgetUsd())
            .thenReturn(saved));
  }

  @GetMapping("/bills")
  public Flux<Map<String, Object>> monthlyBills(
      ServerWebExchange exchange,
      @RequestParam(required = false) String month) {
    YearMonth ym = resolveMonth(month);
    LocalDate firstDay = ym.atDay(1);
    return databaseClient.sql("""
        SELECT
          b.id::text AS bill_id,
          b.bill_month::text AS bill_month,
          b.status AS status,
          b.currency AS currency,
          b.total_requests AS total_requests,
          b.prompt_tokens AS prompt_tokens,
          b.completion_tokens AS completion_tokens,
          b.total_tokens AS total_tokens,
          b.total_cost_usd AS total_cost_usd,
          b.sent_at AS sent_at,
          b.paid_at AS paid_at,
          u.id::text AS user_id,
          u.email AS user_email,
          u.display_name AS user_name
        FROM monthly_bills b
        JOIN gateway_users u ON u.id = b.user_id
        WHERE b.bill_month = :bill_month
        ORDER BY b.total_cost_usd DESC, u.email ASC
        """)
        .bind("bill_month", firstDay)
        .fetch()
        .all();
  }

  @PostMapping("/bills/generate")
  public Mono<Map<String, Object>> generateMonthlyBills(
      ServerWebExchange exchange,
      @RequestParam(required = false) String month) {
    String actor = currentAdmin(exchange).username();
    YearMonth ym = resolveMonth(month);
    LocalDate firstDay = ym.atDay(1);
    Instant start = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return databaseClient.sql("""
        SELECT
          ue.user_id AS user_id,
          count(*) AS total_requests,
          coalesce(sum(ue.billable_prompt_tokens), 0) AS prompt_tokens,
          coalesce(sum(ue.billable_completion_tokens), 0) AS completion_tokens,
          coalesce(sum(ue.total_tokens), 0) AS total_tokens,
          coalesce(sum(ue.estimated_cost_usd), 0) AS total_cost_usd
        FROM usage_events ue
        WHERE ue.created_at >= :start_time
          AND ue.created_at < :end_time
        GROUP BY ue.user_id
        """)
        .bind("start_time", start)
        .bind("end_time", end)
        .fetch()
        .all()
        .flatMap(row -> upsertMonthlyBill(firstDay, row, start, end))
        .filter(Boolean::booleanValue)
        .count()
        .flatMap(count -> auditService.log(actor, "GENERATE_MONTHLY_BILLS", firstDay.toString(), "affected_bills=" + count)
            .thenReturn(Map.of("billMonth", firstDay.toString(), "generatedBills", count)));
  }

  @PostMapping("/bills/{billId}/status")
  public Mono<MonthlyBill> updateBillStatus(
      ServerWebExchange exchange,
      @PathVariable UUID billId,
      @Valid @RequestBody UpdateBillStatusRequest request) {
    String actor = currentAdmin(exchange).username();
    String normalizedStatus = normalizeBillStatus(request.status());
    return monthlyBillRepository.findById(billId)
        .switchIfEmpty(Mono.error(new GatewayException(404, "bill_not_found", "Bill not found")))
        .flatMap(existing -> {
          validateStatusTransition(existing.status(), normalizedStatus);
          MonthlyBill updated = new MonthlyBill(
              existing.id(),
              existing.billMonth(),
              existing.userId(),
              existing.currency(),
              normalizedStatus,
              existing.totalRequests(),
              existing.promptTokens(),
              existing.completionTokens(),
              existing.totalTokens(),
              existing.totalCostUsd(),
              request.note(),
              "SENT".equals(normalizedStatus) ? Instant.now() : existing.sentAt(),
              "PAID".equals(normalizedStatus) ? Instant.now() : existing.paidAt(),
              existing.createdAt(),
              Instant.now());
          return monthlyBillRepository.save(updated)
              .flatMap(saved -> syncUsageStatusWithBill(saved)
                  .then(auditService.log(actor, "UPDATE_BILL_STATUS", saved.id().toString(), "status=" + normalizedStatus))
                  .thenReturn(saved));
        });
  }

  private Mono<Boolean> upsertMonthlyBill(LocalDate firstDay, Map<String, Object> row, Instant start, Instant end) {
    UUID userId = (UUID) row.get("user_id");
    long totalRequests = longValue(row.get("total_requests"));
    long promptTokens = longValue(row.get("prompt_tokens"));
    long completionTokens = longValue(row.get("completion_tokens"));
    long totalTokens = longValue(row.get("total_tokens"));
    BigDecimal totalCost = decimalValue(row.get("total_cost_usd"));

    return monthlyBillRepository.findByBillMonthAndUserId(firstDay, userId)
        .defaultIfEmpty(new MonthlyBill(
            null,
            firstDay,
            userId,
            "USD",
            "DRAFT",
            0,
            0,
            0,
            0,
            BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP),
            null,
            null,
            null,
            Instant.now(),
            Instant.now()))
        .flatMap(existing -> {
          String currentStatus = existing.status() == null ? "DRAFT" : existing.status().toUpperCase(Locale.ROOT);
          if (!"DRAFT".equals(currentStatus) && existing.id() != null) {
            return Mono.just(false);
          }
          MonthlyBill draft = new MonthlyBill(
              existing.id(),
              firstDay,
              userId,
              "USD",
              "DRAFT",
              totalRequests,
              promptTokens,
              completionTokens,
              totalTokens,
              totalCost,
              existing.note(),
              existing.sentAt(),
              existing.paidAt(),
              existing.createdAt() == null ? Instant.now() : existing.createdAt(),
              Instant.now());
          return monthlyBillRepository.save(draft)
              .flatMap(saved -> markUsageStatusForUserMonth(saved.userId(), start, end, "DRAFT"))
              .thenReturn(true);
        });
  }

  private Mono<Void> syncUsageStatusWithBill(MonthlyBill bill) {
    Instant start = bill.billMonth().atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = bill.billMonth().plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return databaseClient.sql("""
        UPDATE usage_events
        SET billing_status = :billing_status,
            billed_at = CASE
              WHEN :billing_status IN ('SENT', 'PAID') THEN now()
              ELSE billed_at
            END
        WHERE user_id = :user_id
          AND created_at >= :start_time
          AND created_at < :end_time
        """)
        .bind("billing_status", bill.status())
        .bind("user_id", bill.userId())
        .bind("start_time", start)
        .bind("end_time", end)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> markUsageStatusForUserMonth(UUID userId, Instant start, Instant end, String status) {
    return databaseClient.sql("""
        UPDATE usage_events
        SET billing_status = :billing_status
        WHERE user_id = :user_id
          AND created_at >= :start_time
          AND created_at < :end_time
          AND (billing_status IS NULL OR billing_status = 'DRAFT')
        """)
        .bind("billing_status", status)
        .bind("user_id", userId)
        .bind("start_time", start)
        .bind("end_time", end)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private String normalizeBillStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new GatewayException(400, "invalid_bill_status", "status is required");
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("DRAFT")
        && !normalized.equals("CONFIRMED")
        && !normalized.equals("SENT")
        && !normalized.equals("PAID")) {
      throw new GatewayException(400, "invalid_bill_status", "status must be DRAFT/CONFIRMED/SENT/PAID");
    }
    return normalized;
  }

  private void validateStatusTransition(String current, String next) {
    String currentStatus = current == null ? "DRAFT" : current.toUpperCase(Locale.ROOT);
    if (currentStatus.equals(next)) {
      return;
    }
    boolean allowed = (currentStatus.equals("DRAFT") && (next.equals("CONFIRMED") || next.equals("SENT")))
        || (currentStatus.equals("CONFIRMED") && (next.equals("SENT") || next.equals("PAID")))
        || (currentStatus.equals("SENT") && next.equals("PAID"));
    if (!allowed) {
      throw new GatewayException(400, "invalid_bill_transition", "unsupported bill status transition");
    }
  }

  private YearMonth resolveMonth(String rawMonth) {
    if (rawMonth == null || rawMonth.isBlank()) {
      return YearMonth.now(ZoneOffset.UTC);
    }
    try {
      return YearMonth.parse(rawMonth.trim());
    } catch (DateTimeParseException ex) {
      throw new GatewayException(400, "invalid_month", "month must use YYYY-MM format");
    }
  }

  private long longValue(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(value.toString());
  }

  private BigDecimal decimalValue(Object value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.setScale(8, RoundingMode.HALF_UP);
    }
    return new BigDecimal(value.toString()).setScale(8, RoundingMode.HALF_UP);
  }

  private AdminPrincipal currentAdmin(ServerWebExchange exchange) {
    AdminPrincipal principal = exchange.getAttribute(AdminSecurityWebFilter.ADMIN_PRINCIPAL_ATTR);
    if (principal == null) {
      throw new GatewayException(401, "admin_missing_context", "Admin auth context is missing");
    }
    return principal;
  }

  public record CreatePricingModelRequest(
      @NotBlank String provider,
      @NotBlank String modelPattern,
      @NotNull @DecimalMin("0.0") BigDecimal promptPricePer1mTokens,
      @NotNull @DecimalMin("0.0") BigDecimal completionPricePer1mTokens,
      Instant effectiveFrom) {}

  public record UpsertBillingPolicyRequest(
      @NotNull UUID userId,
      @NotNull @DecimalMin("0.0") BigDecimal monthlyBudgetUsd,
      @NotNull @DecimalMin("1.0") @DecimalMax("100.0") BigDecimal alertThresholdPercent,
      boolean autoDisableApiKeys,
      String webhookUrl) {}

  public record UpdateBillStatusRequest(@NotBlank String status, String note) {}
}
