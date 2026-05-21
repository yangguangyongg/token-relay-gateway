package com.tokenrelay.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenrelay.gateway.auth.AuthContext;
import com.tokenrelay.gateway.domain.UsageEvent;
import com.tokenrelay.gateway.config.GatewayProperties;
import com.tokenrelay.gateway.repository.ApiKeyRepository;
import com.tokenrelay.gateway.repository.UsageEventRepository;
import com.tokenrelay.gateway.service.ApiKeyPolicyService;
import com.tokenrelay.gateway.service.AuthService;
import com.tokenrelay.gateway.service.BillingControlService;
import com.tokenrelay.gateway.service.ComplianceService;
import com.tokenrelay.gateway.service.GatewayRequestFactory;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.ModelPricingService;
import com.tokenrelay.gateway.service.ProtocolCompatibilityService;
import com.tokenrelay.gateway.service.QuotaService;
import com.tokenrelay.gateway.service.RateLimitService;
import com.tokenrelay.gateway.service.UsageMeteringService;
import com.tokenrelay.gateway.service.WorkspaceModelPolicyService;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.Exceptions;

@RestController
public class ProxyController {
  private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

  private final AuthService authService;
  private final ApiKeyRepository apiKeys;
  private final ComplianceService complianceService;
  private final ApiKeyPolicyService apiKeyPolicyService;
  private final RateLimitService rateLimitService;
  private final QuotaService quotaService;
  private final ProviderRouter router;
  private final UsageEventRepository usageEvents;
  private final UsageMeteringService usageMeteringService;
  private final ModelPricingService modelPricingService;
  private final BillingControlService billingControlService;
  private final WorkspaceModelPolicyService workspaceModelPolicyService;
  private final GatewayRequestFactory gatewayRequestFactory;
  private final ProtocolCompatibilityService protocolCompatibilityService;
  private final ObjectMapper objectMapper;
  private final Duration providerTimeout;

  public ProxyController(
      AuthService authService,
      ApiKeyRepository apiKeys,
      ComplianceService complianceService,
      ApiKeyPolicyService apiKeyPolicyService,
      RateLimitService rateLimitService,
      QuotaService quotaService,
      ProviderRouter router,
      UsageEventRepository usageEvents,
      UsageMeteringService usageMeteringService,
      ModelPricingService modelPricingService,
      BillingControlService billingControlService,
      WorkspaceModelPolicyService workspaceModelPolicyService,
      GatewayRequestFactory gatewayRequestFactory,
      ProtocolCompatibilityService protocolCompatibilityService,
      GatewayProperties gatewayProperties,
      ObjectMapper objectMapper) {
    this.authService = authService;
    this.apiKeys = apiKeys;
    this.complianceService = complianceService;
    this.apiKeyPolicyService = apiKeyPolicyService;
    this.rateLimitService = rateLimitService;
    this.quotaService = quotaService;
    this.router = router;
    this.usageEvents = usageEvents;
    this.usageMeteringService = usageMeteringService;
    this.modelPricingService = modelPricingService;
    this.billingControlService = billingControlService;
    this.workspaceModelPolicyService = workspaceModelPolicyService;
    this.gatewayRequestFactory = gatewayRequestFactory;
    this.protocolCompatibilityService = protocolCompatibilityService;
    this.objectMapper = objectMapper;
    this.providerTimeout = gatewayProperties.providerTimeout();
  }

  @PostMapping(value = "/v1/chat/completions", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public Mono<ResponseEntity<?>> chatCompletions(@RequestBody JsonNode request, ServerWebExchange exchange) {
    return proxy(gatewayRequestFactory.chatCompletions(request), exchange);
  }

  @PostMapping(value = "/v1/responses", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public Mono<ResponseEntity<?>> responses(@RequestBody JsonNode request, ServerWebExchange exchange) {
    return proxy(gatewayRequestFactory.responses(request), exchange);
  }

  @PostMapping(value = "/v1/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<?>> embeddings(@RequestBody JsonNode request, ServerWebExchange exchange) {
    return proxy(gatewayRequestFactory.embeddings(request), exchange);
  }

  @PostMapping(value = "/anthropic/v1/messages", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public Mono<ResponseEntity<?>> anthropicMessages(@RequestBody JsonNode request, ServerWebExchange exchange) {
    return proxy(gatewayRequestFactory.anthropicMessages(request), exchange);
  }

  private Mono<ResponseEntity<?>> proxy(GatewayRequest gatewayRequest, ServerWebExchange exchange) {
    String requestId = UUID.randomUUID().toString();
    return authService.authenticate(exchange)
        .flatMap(context -> complianceService.check(exchange).thenReturn(context))
        .flatMap(context -> apiKeyPolicyService.validate(context.apiKey(), gatewayRequest.routingBody()).thenReturn(context))
        .flatMap(context -> workspaceModelPolicyService.validate(context.workspace().id(), gatewayRequest.routingBody()).thenReturn(context))
        .flatMap(context -> rateLimitService.check(context.apiKey()).thenReturn(context))
        .flatMap(context -> quotaService.reserve(context).thenReturn(context))
        .flatMap(context -> routeWithFallback(context, gatewayRequest, requestId));
  }

  private Mono<ResponseEntity<?>> routeWithFallback(AuthContext context, GatewayRequest gatewayRequest, String requestId) {
    return router.candidates(context, gatewayRequest).flatMap(candidates -> {
      if (candidates.isEmpty()) {
        return Mono.error(new GatewayException(503, "no_provider", "No active provider key can serve this model"));
      }
      return tryCandidate(context, gatewayRequest, requestId, candidates, 0);
    });
  }

  private Mono<ResponseEntity<?>> tryCandidate(
      AuthContext context,
      GatewayRequest gatewayRequest,
      String requestId,
      List<ProviderRouter.RouteCandidate> candidates,
      int index) {
    ProviderRouter.RouteCandidate candidate = candidates.get(index);
    UsageMeteringService.UsageTracker usageTracker =
        usageMeteringService.newTracker(candidate.providerKey().provider(), gatewayRequest.routingBody());
    return candidate.adapter().execute(candidate.providerKey(), gatewayRequest)
        .timeout(providerTimeout)
        .flatMap(providerResponse -> gatewayRequest.stream()
            ? Mono.just(buildStreamingResponse(context, candidate, gatewayRequest, requestId, providerResponse, usageTracker))
            : buildNonStreamingResponse(context, candidate, gatewayRequest, requestId, providerResponse, usageTracker))
        .onErrorResume(error -> {
          if (index + 1 < candidates.size()) {
            return tryCandidate(context, gatewayRequest, requestId, candidates, index + 1);
          }
          return Mono.error(mapProviderError(error));
        });
  }

  private ResponseEntity<?> buildStreamingResponse(
      AuthContext context,
      ProviderRouter.RouteCandidate candidate,
      GatewayRequest gatewayRequest,
      String requestId,
      ProviderResponse providerResponse,
      UsageMeteringService.UsageTracker usageTracker) {
    AtomicBoolean usageSaved = new AtomicBoolean(false);
    ResponseEntity<Flux<String>> response = providerResponse.response();
    Flux<String> upstreamBody = response.getBody()
        .timeout(providerTimeout)
        .doOnNext(usageTracker::onChunk)
        .doOnCancel(() -> log.info(
            "Streaming client disconnected requestId={} provider={} workspaceId={}",
            requestId,
            candidate.providerKey().provider(),
            context.workspace().id()));
    Flux<String> body = protocolCompatibilityService
        .transformStreaming(providerResponse.protocol(), gatewayRequest, upstreamBody)
        .doFinally(signal -> {
          if (signal == SignalType.CANCEL) {
            log.info(
                "Streaming request cancelled requestId={} provider={} workspaceId={}",
                requestId,
                candidate.providerKey().provider(),
                context.workspace().id());
          }
          persistUsageOnce(
              context,
              candidate,
              gatewayRequest.routingBody(),
              response.getStatusCode().value(),
              requestId,
              usageTracker,
              usageSaved,
              signal);
        });
    ResponseEntity.BodyBuilder builder = responseBuilder(
        context,
        candidate,
        requestId,
        response.getStatusCode().value(),
        protocolCompatibilityService.responseContentType(gatewayRequest));
    if (protocolCompatibilityService.requiresRawStreaming(providerResponse.protocol(), gatewayRequest)) {
      return builder.body(toServerSentEvents(body));
    }
    return builder.body(body);
  }

  private Flux<ServerSentEvent<String>> toServerSentEvents(Flux<String> frames) {
    return frames.concatMap(frame -> Flux.fromIterable(parseServerSentEvents(frame)));
  }

  private List<ServerSentEvent<String>> parseServerSentEvents(String frame) {
    List<ServerSentEvent<String>> events = new ArrayList<>();
    if (frame == null || frame.isBlank()) {
      return events;
    }
    String normalized = frame.replace("\r\n", "\n");
    for (String block : normalized.split("\n\n")) {
      if (block.isBlank()) {
        continue;
      }
      String eventName = null;
      StringBuilder data = new StringBuilder();
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          eventName = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          if (data.length() > 0) {
            data.append('\n');
          }
          data.append(line.substring("data:".length()).trim());
        } else if (data.length() == 0) {
          data.append(line.trim());
        }
      }
      if (data.length() == 0) {
        continue;
      }
      ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(data.toString());
      if (eventName != null && !eventName.isBlank()) {
        builder.event(eventName);
      }
      events.add(builder.build());
    }
    return events;
  }

  private Mono<ResponseEntity<?>> buildNonStreamingResponse(
      AuthContext context,
      ProviderRouter.RouteCandidate candidate,
      GatewayRequest gatewayRequest,
      String requestId,
      ProviderResponse providerResponse,
      UsageMeteringService.UsageTracker usageTracker) {
    ResponseEntity<Flux<String>> response = providerResponse.response();
    return response.getBody()
        .timeout(providerTimeout)
        .doOnNext(usageTracker::onChunk)
        .reduceWith(StringBuilder::new, StringBuilder::append)
        .map(StringBuilder::toString)
        .flatMap(bodyText -> {
          ResponseEntity<?> entity = responseBuilder(
                  context,
                  candidate,
                  requestId,
                  response.getStatusCode().value(),
                  protocolCompatibilityService.responseContentType(gatewayRequest))
              .body(protocolCompatibilityService.transformNonStreaming(
                  providerResponse.protocol(),
                  gatewayRequest,
                  bodyText));
          return saveUsage(
                  context,
                  candidate,
                  gatewayRequest.routingBody(),
                  response.getStatusCode().value(),
                  requestId,
                  usageTracker)
              .thenReturn(entity);
        });
  }

  private ResponseEntity.BodyBuilder responseBuilder(
      AuthContext context,
      ProviderRouter.RouteCandidate candidate,
      String requestId,
      int statusCode,
      MediaType contentType) {
    return ResponseEntity.status(statusCode)
        .header("X-Request-Id", requestId)
        .header("X-Provider", candidate.providerKey().provider())
        .header("X-Provider-Scope", candidate.byokScope() ? "USER_BYOK" : "PLATFORM_SHARED")
        .header("X-Workspace-Id", context.workspace().id().toString())
        .contentType(contentType);
  }

  private void persistUsageOnce(
      AuthContext context,
      ProviderRouter.RouteCandidate candidate,
      JsonNode request,
      int statusCode,
      String requestId,
      UsageMeteringService.UsageTracker usageTracker,
      AtomicBoolean usageSaved,
      SignalType signal) {
    if (!usageSaved.compareAndSet(false, true)) {
      return;
    }
    saveUsage(context, candidate, request, statusCode, requestId, usageTracker)
        .doOnError(error -> log.warn(
            "Failed to persist usage for requestId={} provider={} signal={}",
            requestId,
            candidate.providerKey().provider(),
            signal,
            error))
        .onErrorResume(error -> Mono.empty())
        .subscribe();
  }

  private GatewayException mapProviderError(Throwable error) {
    Throwable root = Exceptions.unwrap(error);
    if (root instanceof GatewayException gatewayException) {
      return gatewayException;
    }
    if (root instanceof TimeoutException) {
      return new GatewayException(504, "provider_timeout", "Provider response timed out");
    }
    String message = root == null || root.getMessage() == null || root.getMessage().isBlank()
        ? "Provider request failed"
        : root.getMessage();
    return new GatewayException(502, "provider_unavailable", message);
  }

  private Mono<Void> saveUsage(
      AuthContext context,
      ProviderRouter.RouteCandidate candidate,
      JsonNode request,
      int statusCode,
      String requestId,
      UsageMeteringService.UsageTracker usageTracker) {
    UsageMeteringService.UsageSnapshot usage = usageTracker.snapshot();
    String model = request.path("model").asText("unknown");
    return modelPricingService.resolve(candidate.providerKey().provider(), model)
        .flatMap(pricing -> {
          BigDecimal estimatedCost = modelPricingService.estimateCost(pricing, usage.promptTokens(), usage.completionTokens());
          UsageEvent event = new UsageEvent(
              null,
              context.user().id(),
              context.apiKey().id(),
              candidate.providerKey().provider(),
              model,
              usage.promptTokens(),
              usage.completionTokens(),
              usage.totalTokens(),
              usage.promptTokens(),
              usage.completionTokens(),
              estimatedCost,
              pricing.pricingRuleId(),
              "DRAFT",
              null,
              statusCode,
              requestId,
              null);
          return usageEvents.save(event)
              .flatMap(saved -> apiKeys.save(new com.tokenrelay.gateway.domain.ApiKeyRecord(
                      context.apiKey().id(),
                      context.apiKey().userId(),
                      context.apiKey().workspaceId(),
                      context.apiKey().name(),
                      context.apiKey().keyPrefix(),
                      context.apiKey().keyHash(),
                      context.apiKey().status(),
                      context.apiKey().rateLimitPerMinute(),
                      context.apiKey().monthlyTokenQuota(),
                      context.apiKey().createdAt(),
                      Instant.now()))
                  .thenReturn(saved))
              .flatMap(saved -> billingControlService.evaluate(saved.userId(), estimatedCost, requestId))
              .then();
        });
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
