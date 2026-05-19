package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.ProviderKey;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ProviderHealthCheckService {
  private final WebClient webClient;

  public ProviderHealthCheckService(WebClient webClient) {
    this.webClient = webClient;
  }

  public Mono<ProviderCheckResult> check(ProviderKey key) {
    Instant startedAt = Instant.now();
    return probe(key)
        .timeout(Duration.ofSeconds(20))
        .map(probe -> toResult(probe.statusCode(), probe.body(), startedAt))
        .onErrorResume(error -> Mono.just(new ProviderCheckResult(
            "UNHEALTHY",
            -1,
            compact("probe_failed: " + error.getMessage()),
            Instant.now(),
            Duration.between(startedAt, Instant.now()).toMillis())));
  }

  private Mono<ProbeResponse> probe(ProviderKey key) {
    String provider = normalize(key.provider());
    return switch (provider) {
      case "OPENAI" -> executeGet(
          trimSlash(key.baseUrl()) + "/v1/models",
          headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey()));
      case "ANTHROPIC" -> executeGet(
          trimSlash(key.baseUrl()) + "/v1/models",
          headers -> {
            headers.set("x-api-key", key.apiKey());
            headers.set("anthropic-version", "2023-06-01");
          });
      case "AZURE_OPENAI" -> executeGet(
          trimSlash(key.baseUrl()) + "/openai/models?api-version=2024-10-21",
          headers -> headers.set("api-key", key.apiKey()));
      case "GEMINI" -> executeGet(
          trimSlash(key.baseUrl()) + "/v1beta/openai/models",
          headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey()));
      default -> Mono.just(new ProbeResponse(400, "unsupported_provider:" + provider));
    };
  }

  private Mono<ProbeResponse> executeGet(String url, java.util.function.Consumer<HttpHeaders> headerConsumer) {
    return webClient.get()
        .uri(URI.create(url))
        .headers(headerConsumer)
        .exchangeToMono(response -> response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> new ProbeResponse(response.statusCode().value(), body)));
  }

  private ProviderCheckResult toResult(int statusCode, String responseBody, Instant startedAt) {
    String healthStatus = statusCode >= 200 && statusCode < 300 ? "HEALTHY" : "UNHEALTHY";
    String message;
    if (statusCode >= 200 && statusCode < 300) {
      message = "ok";
    } else if (statusCode == 401 || statusCode == 403) {
      message = "auth_failed";
    } else {
      message = "http_" + statusCode;
    }
    if (responseBody != null && !responseBody.isBlank()) {
      message = message + " " + compact(responseBody);
    }
    return new ProviderCheckResult(
        healthStatus,
        statusCode,
        message,
        Instant.now(),
        Duration.between(startedAt, Instant.now()).toMillis());
  }

  private String trimSlash(String value) {
    return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private String compact(String value) {
    if (value == null) {
      return "";
    }
    String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
    return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240);
  }

  public record ProviderCheckResult(
      String healthStatus,
      int statusCode,
      String message,
      Instant checkedAt,
      long latencyMs) {}

  private record ProbeResponse(int statusCode, String body) {}
}
