package com.tokenrelay.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenrelay.gateway.auth.AuthContext;
import com.tokenrelay.gateway.domain.UsageEvent;
import com.tokenrelay.gateway.repository.UsageEventRepository;
import com.tokenrelay.gateway.service.AuthService;
import com.tokenrelay.gateway.service.ComplianceService;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.QuotaService;
import com.tokenrelay.gateway.service.RateLimitService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class ProxyController {
  private final AuthService authService;
  private final ComplianceService complianceService;
  private final RateLimitService rateLimitService;
  private final QuotaService quotaService;
  private final ProviderRouter router;
  private final UsageEventRepository usageEvents;
  private final ObjectMapper objectMapper;

  public ProxyController(
      AuthService authService,
      ComplianceService complianceService,
      RateLimitService rateLimitService,
      QuotaService quotaService,
      ProviderRouter router,
      UsageEventRepository usageEvents,
      ObjectMapper objectMapper) {
    this.authService = authService;
    this.complianceService = complianceService;
    this.rateLimitService = rateLimitService;
    this.quotaService = quotaService;
    this.router = router;
    this.usageEvents = usageEvents;
    this.objectMapper = objectMapper;
  }

  @PostMapping(value = "/v1/chat/completions", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public Mono<ResponseEntity<Flux<String>>> chatCompletions(@RequestBody JsonNode request, ServerWebExchange exchange) {
    String requestId = UUID.randomUUID().toString();
    return authService.authenticate(exchange)
        .flatMap(context -> complianceService.check(exchange).thenReturn(context))
        .flatMap(context -> rateLimitService.check(context.apiKey()).thenReturn(context))
        .flatMap(context -> quotaService.reserve(context).thenReturn(context))
        .flatMap(context -> routeWithFallback(context, request, requestId));
  }

  private Mono<ResponseEntity<Flux<String>>> routeWithFallback(AuthContext context, JsonNode request, String requestId) {
    return router.candidates(request).flatMap(candidates -> {
      if (candidates.isEmpty()) {
        return Mono.error(new GatewayException(503, "no_provider", "No active provider key can serve this model"));
      }
      return tryCandidate(context, request, requestId, candidates, 0);
    });
  }

  private Mono<ResponseEntity<Flux<String>>> tryCandidate(
      AuthContext context,
      JsonNode request,
      String requestId,
      List<ProviderRouter.RouteCandidate> candidates,
      int index) {
    ProviderRouter.RouteCandidate candidate = candidates.get(index);
    return candidate.adapter().stream(candidate.providerKey(), request)
        .map(response -> {
          Flux<String> body = response.getBody()
              .doFinally(signal -> saveUsage(context, candidate, request, response.getStatusCode().value(), requestId).subscribe());
          return ResponseEntity.status(response.getStatusCode())
              .header("X-Request-Id", requestId)
              .header("X-Provider", candidate.providerKey().provider())
              .contentType(response.getHeaders().getContentType() == null ? MediaType.APPLICATION_JSON : response.getHeaders().getContentType())
              .body(body);
        })
        .onErrorResume(error -> {
          if (index + 1 < candidates.size()) {
            return tryCandidate(context, request, requestId, candidates, index + 1);
          }
          return Mono.error(new GatewayException(502, "provider_unavailable", error.getMessage()));
        });
  }

  private Mono<Void> saveUsage(
      AuthContext context,
      ProviderRouter.RouteCandidate candidate,
      JsonNode request,
      int statusCode,
      String requestId) {
    long promptTokens = estimateTokens(request.path("messages").toString());
    long completionTokens = 0;
    long totalTokens = promptTokens + completionTokens;
    UsageEvent event = new UsageEvent(
        null,
        context.user().id(),
        context.apiKey().id(),
        candidate.providerKey().provider(),
        request.path("model").asText("unknown"),
        promptTokens,
        completionTokens,
        totalTokens,
        statusCode,
        requestId,
        null);
    return usageEvents.save(event).then();
  }

  private long estimateTokens(String text) {
    return Math.max(1, text.length() / 4);
  }

  @ExceptionHandler(GatewayException.class)
  public ResponseEntity<JsonNode> gatewayError(GatewayException ex) {
    return ResponseEntity.status(ex.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.createObjectNode()
            .put("error", ex.code())
            .put("message", ex.getMessage()));
  }
}
