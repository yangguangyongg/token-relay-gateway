package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.config.AdminSecurityWebFilter;
import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.domain.AuditLog;
import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.repository.ApiKeyRepository;
import com.tokenrelay.gateway.repository.AuditLogRepository;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.ProviderKeyRepository;
import com.tokenrelay.gateway.service.AuditService;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.HashService;
import com.tokenrelay.gateway.service.ProviderKeySecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class AdminController {
  private final GatewayUserRepository users;
  private final ApiKeyRepository apiKeys;
  private final ProviderKeyRepository providerKeys;
  private final AuditLogRepository auditLogs;
  private final HashService hashService;
  private final ProviderKeySecurityService providerKeySecurity;
  private final AuditService auditService;
  private final DatabaseClient databaseClient;
  private final SecureRandom secureRandom = new SecureRandom();

  public AdminController(
      GatewayUserRepository users,
      ApiKeyRepository apiKeys,
      ProviderKeyRepository providerKeys,
      AuditLogRepository auditLogs,
      HashService hashService,
      ProviderKeySecurityService providerKeySecurity,
      AuditService auditService,
      R2dbcEntityTemplate template) {
    this.users = users;
    this.apiKeys = apiKeys;
    this.providerKeys = providerKeys;
    this.auditLogs = auditLogs;
    this.hashService = hashService;
    this.providerKeySecurity = providerKeySecurity;
    this.auditService = auditService;
    this.databaseClient = template.getDatabaseClient();
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/admin/users")
  public Flux<GatewayUser> users(ServerWebExchange exchange) {
    return users.findAll();
  }

  @PostMapping("/admin/users")
  public Mono<GatewayUser> createUser(ServerWebExchange exchange, @Valid @RequestBody CreateUserRequest request) {
    String actor = currentAdmin(exchange).username();
    GatewayUser user = new GatewayUser(null, request.email(), request.displayName(), "ACTIVE", request.monthlyTokenQuota(), null);
    return users.save(user)
        .flatMap(saved -> databaseClient.sql("""
            INSERT INTO user_billing_policies (user_id, currency, monthly_budget_usd, alert_threshold_percent, auto_disable_api_keys, status)
            VALUES (:user_id, 'USD', 0, 80, false, 'ACTIVE')
            ON CONFLICT (user_id) DO NOTHING
            """)
            .bind("user_id", saved.id())
            .fetch()
            .rowsUpdated()
            .thenReturn(saved))
        .flatMap(saved -> auditService.log(actor, "CREATE_USER", saved.id().toString(), saved.email()).thenReturn(saved));
  }

  @GetMapping("/admin/api-keys")
  public Flux<ApiKeyRecord> apiKeys(ServerWebExchange exchange) {
    return apiKeys.findAll();
  }

  @PostMapping("/admin/api-keys")
  public Mono<ResponseEntity<CreateApiKeyResponse>> createApiKey(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateApiKeyRequest request) {
    String actor = currentAdmin(exchange).username();
    String rawKey = "tg_" + randomHex(32);
    ApiKeyRecord key = new ApiKeyRecord(
        null,
        request.userId(),
        request.name(),
        rawKey.substring(0, 10),
        hashService.sha256(rawKey),
        "ACTIVE",
        request.rateLimitPerMinute(),
        null,
        null);
    return apiKeys.save(key)
        .flatMap(saved -> auditService.log(actor, "CREATE_API_KEY", saved.id().toString(), saved.keyPrefix()).thenReturn(saved))
        .map(saved -> ResponseEntity.ok(new CreateApiKeyResponse(saved, rawKey)));
  }

  @GetMapping("/admin/provider-keys")
  public Flux<ProviderKeyView> providerKeys(ServerWebExchange exchange) {
    return providerKeys.findAll().map(this::toProviderKeyView);
  }

  @PostMapping("/admin/provider-keys")
  public Mono<ProviderKeyView> createProviderKey(
      ServerWebExchange exchange,
      @Valid @RequestBody CreateProviderKeyRequest request) {
    String actor = currentAdmin(exchange).username();
    ProviderKey providerKey = new ProviderKey(
        null,
        request.provider(),
        request.name(),
        request.baseUrl(),
        request.apiKey(),
        request.azureDeployment(),
        "ACTIVE",
        request.priority(),
        null);
    return providerKeys.save(providerKeySecurity.encryptForStorage(providerKey))
        .flatMap(saved -> auditService.log(actor, "CREATE_PROVIDER_KEY", saved.id().toString(), saved.provider()).thenReturn(saved))
        .map(this::toProviderKeyView);
  }

  @GetMapping("/admin/audit-logs")
  public Flux<AuditLog> auditLogs(ServerWebExchange exchange) {
    return auditLogs.findAll();
  }

  @GetMapping("/admin/usage-summary")
  public Flux<Map<String, Object>> usageSummary(ServerWebExchange exchange) {
    return databaseClient.sql("""
        SELECT
          provider,
          model,
          count(*) AS requests,
          coalesce(sum(total_tokens), 0) AS total_tokens,
          coalesce(sum(estimated_cost_usd), 0) AS total_cost_usd
        FROM usage_events
        GROUP BY provider, model
        ORDER BY requests DESC
        """)
        .fetch()
        .all();
  }

  @GetMapping("/admin/usage-details")
  public Flux<Map<String, Object>> usageDetails(
      ServerWebExchange exchange,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) UUID userId) {
    TimeRange monthRange = resolveMonthRange(month);
    return usageDetailQuery(monthRange, userId)
        .fetch()
        .all();
  }

  @GetMapping("/admin/billing/monthly.csv")
  public Mono<ResponseEntity<String>> monthlyBillingCsv(
      ServerWebExchange exchange,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) UUID userId) {
    TimeRange monthRange = resolveMonthRange(month);
    return usageDetailQuery(monthRange, userId)
        .fetch()
        .all()
        .collectList()
        .map(rows -> ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + billingCsvFileName(monthRange.month(), userId) + "\"")
            .body(toBillingCsv(monthRange.month(), rows)));
  }

  private AdminPrincipal currentAdmin(ServerWebExchange exchange) {
    AdminPrincipal principal = exchange.getAttribute(AdminSecurityWebFilter.ADMIN_PRINCIPAL_ATTR);
    if (principal == null) {
      throw new GatewayException(401, "admin_missing_context", "Admin auth context is missing");
    }
    return principal;
  }

  private String randomHex(int bytes) {
    byte[] buffer = new byte[bytes];
    secureRandom.nextBytes(buffer);
    return HexFormat.of().formatHex(buffer);
  }

  private GenericExecuteSpec usageDetailQuery(TimeRange monthRange, UUID userId) {
    String sql = """
        SELECT
          u.id::text AS user_id,
          u.email AS user_email,
          u.display_name AS user_name,
          ue.provider AS provider,
          ue.model AS model,
          count(*) AS requests,
          coalesce(sum(ue.prompt_tokens), 0) AS prompt_tokens,
          coalesce(sum(ue.completion_tokens), 0) AS completion_tokens,
          coalesce(sum(ue.total_tokens), 0) AS total_tokens,
          coalesce(sum(ue.estimated_cost_usd), 0) AS total_cost_usd,
          max(ue.billing_status) AS billing_status,
          max(ue.created_at) AS last_request_at
        FROM usage_events ue
        JOIN gateway_users u ON u.id = ue.user_id
        WHERE ue.created_at >= :start_time
          AND ue.created_at < :end_time
        """;
    if (userId != null) {
      sql += " AND ue.user_id = :user_id\n";
    }
    sql += """
        GROUP BY u.id, u.email, u.display_name, ue.provider, ue.model
        ORDER BY u.email ASC, total_tokens DESC, ue.provider ASC, ue.model ASC
        """;

    GenericExecuteSpec spec = databaseClient.sql(sql)
        .bind("start_time", monthRange.startInclusive())
        .bind("end_time", monthRange.endExclusive());
    if (userId != null) {
      spec = spec.bind("user_id", userId);
    }
    return spec;
  }

  private TimeRange resolveMonthRange(String rawMonth) {
    YearMonth month;
    if (rawMonth == null || rawMonth.isBlank()) {
      month = YearMonth.now(ZoneOffset.UTC);
    } else {
      try {
        month = YearMonth.parse(rawMonth.trim());
      } catch (DateTimeParseException ex) {
        throw new GatewayException(400, "invalid_month", "month must use YYYY-MM format");
      }
    }

    Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return new TimeRange(month, start, end);
  }

  private String billingCsvFileName(YearMonth month, UUID userId) {
    if (userId == null) {
      return "billing-" + month + ".csv";
    }
    return "billing-" + month + "-user-" + userId + ".csv";
  }

  private String toBillingCsv(YearMonth month, List<Map<String, Object>> rows) {
    StringBuilder csv = new StringBuilder();
    csv.append("month,user_id,user_email,user_name,provider,model,requests,prompt_tokens,completion_tokens,total_tokens,total_cost_usd,billing_status,last_request_at\n");
    for (Map<String, Object> row : rows) {
      csv.append(csvCell(month.toString())).append(",");
      csv.append(csvCell(stringValue(row.get("user_id")))).append(",");
      csv.append(csvCell(stringValue(row.get("user_email")))).append(",");
      csv.append(csvCell(stringValue(row.get("user_name")))).append(",");
      csv.append(csvCell(stringValue(row.get("provider")))).append(",");
      csv.append(csvCell(stringValue(row.get("model")))).append(",");
      csv.append(csvCell(stringValue(row.get("requests")))).append(",");
      csv.append(csvCell(stringValue(row.get("prompt_tokens")))).append(",");
      csv.append(csvCell(stringValue(row.get("completion_tokens")))).append(",");
      csv.append(csvCell(stringValue(row.get("total_tokens")))).append(",");
      csv.append(csvCell(stringValue(row.get("total_cost_usd")))).append(",");
      csv.append(csvCell(stringValue(row.get("billing_status")))).append(",");
      csv.append(csvCell(stringValue(row.get("last_request_at")))).append("\n");
    }

    if (!rows.isEmpty()) {
      csv.append("\n");
      csv.append("month,user_id,user_email,user_name,total_requests,total_tokens,total_cost_usd\n");
      for (Map<String, Object> summary : summarizeByUser(rows)) {
        csv.append(csvCell(month.toString())).append(",");
        csv.append(csvCell(stringValue(summary.get("user_id")))).append(",");
        csv.append(csvCell(stringValue(summary.get("user_email")))).append(",");
        csv.append(csvCell(stringValue(summary.get("user_name")))).append(",");
        csv.append(csvCell(stringValue(summary.get("requests")))).append(",");
        csv.append(csvCell(stringValue(summary.get("total_tokens")))).append(",");
        csv.append(csvCell(stringValue(summary.get("total_cost_usd")))).append("\n");
      }
    }

    return csv.toString();
  }

  private List<Map<String, Object>> summarizeByUser(List<Map<String, Object>> rows) {
    record UserAgg(String userId, String userEmail, String userName, long requests, long totalTokens, java.math.BigDecimal totalCostUsd) {}
    Map<String, UserAgg> accumulator = new java.util.LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String userId = stringValue(row.get("user_id"));
      String userEmail = stringValue(row.get("user_email"));
      String userName = stringValue(row.get("user_name"));
      long requests = longValue(row.get("requests"));
      long totalTokens = longValue(row.get("total_tokens"));
      java.math.BigDecimal totalCost = decimalValue(row.get("total_cost_usd"));
      String key = userId + "|" + userEmail;
      UserAgg existing = accumulator.get(key);
      if (existing == null) {
        accumulator.put(key, new UserAgg(userId, userEmail, userName, requests, totalTokens, totalCost));
      } else {
        accumulator.put(key, new UserAgg(
            existing.userId(),
            existing.userEmail(),
            existing.userName(),
            existing.requests() + requests,
            existing.totalTokens() + totalTokens,
            existing.totalCostUsd().add(totalCost)));
      }
    }

    List<Map<String, Object>> summarized = new ArrayList<>();
    for (UserAgg value : accumulator.values()) {
      summarized.add(Map.of(
          "user_id", value.userId(),
          "user_email", value.userEmail(),
          "user_name", value.userName(),
          "requests", value.requests(),
          "total_tokens", value.totalTokens(),
          "total_cost_usd", value.totalCostUsd()));
    }
    return summarized;
  }

  private long longValue(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  private String stringValue(Object value) {
    return value == null ? "" : value.toString();
  }

  private java.math.BigDecimal decimalValue(Object value) {
    if (value == null) {
      return java.math.BigDecimal.ZERO;
    }
    if (value instanceof java.math.BigDecimal decimal) {
      return decimal;
    }
    return new java.math.BigDecimal(value.toString());
  }

  private String csvCell(String value) {
    if (value == null) {
      return "";
    }
    String escaped = value.replace("\"", "\"\"");
    return "\"" + escaped + "\"";
  }

  private record TimeRange(YearMonth month, Instant startInclusive, Instant endExclusive) {}

  public record CreateUserRequest(@Email String email, @NotBlank String displayName, long monthlyTokenQuota) {}

  public record CreateApiKeyRequest(UUID userId, @NotBlank String name, int rateLimitPerMinute) {}

  public record CreateApiKeyResponse(ApiKeyRecord apiKey, String rawKey) {}

  public record CreateProviderKeyRequest(
      @NotBlank String provider,
      @NotBlank String name,
      @NotBlank String baseUrl,
      @NotBlank String apiKey,
      String azureDeployment,
      int priority) {}

  public record ProviderKeyView(
      UUID id,
      String provider,
      String name,
      String baseUrl,
      String apiKeyMasked,
      String azureDeployment,
      String status,
      int priority,
      Instant createdAt) {}

  private ProviderKeyView toProviderKeyView(ProviderKey key) {
    return new ProviderKeyView(
        key.id(),
        key.provider(),
        key.name(),
        key.baseUrl(),
        providerKeySecurity.maskStored(key.apiKey()),
        key.azureDeployment(),
        key.status(),
        key.priority(),
        key.createdAt());
  }
}
