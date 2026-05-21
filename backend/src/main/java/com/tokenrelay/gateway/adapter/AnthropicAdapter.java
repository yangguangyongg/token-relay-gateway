package com.tokenrelay.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.proxy.GatewayOperation;
import com.tokenrelay.gateway.proxy.GatewayRequest;
import com.tokenrelay.gateway.proxy.ProviderProtocol;
import com.tokenrelay.gateway.proxy.ProviderResponse;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class AnthropicAdapter implements ProviderAdapter {
  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  public AnthropicAdapter(WebClient webClient, ObjectMapper objectMapper) {
    this.webClient = webClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean supports(ProviderKey key, GatewayRequest request) {
    if (request.operation() == GatewayOperation.EMBEDDINGS) {
      return false;
    }
    String model = request.routingBody().path("model").asText("");
    return "ANTHROPIC".equalsIgnoreCase(key.provider()) || model.startsWith("claude-");
  }

  @Override
  public Mono<ProviderResponse> execute(ProviderKey key, GatewayRequest request) {
    return webClient.post()
        .uri(URI.create(trimSlash(key.baseUrl()) + "/v1/messages"))
        .header("x-api-key", key.apiKey())
        .header("anthropic-version", "2023-06-01")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(request.anthropicMessagesBody()))
        .retrieve()
        .toEntityFlux(String.class)
        .map(response -> new ProviderResponse(ProviderProtocol.ANTHROPIC_MESSAGES, response));
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
