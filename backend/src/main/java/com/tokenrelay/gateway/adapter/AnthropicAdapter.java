package com.tokenrelay.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenrelay.gateway.domain.ProviderKey;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
  public boolean supports(ProviderKey key, JsonNode request) {
    String model = request.path("model").asText("");
    return "ANTHROPIC".equalsIgnoreCase(key.provider()) || model.startsWith("claude-");
  }

  @Override
  public Mono<ResponseEntity<Flux<String>>> stream(ProviderKey key, JsonNode request) {
    ObjectNode body = objectMapper.createObjectNode();
    body.set("model", request.path("model"));
    body.set("messages", request.path("messages"));
    body.put("stream", request.path("stream").asBoolean(false));
    body.put("max_tokens", request.path("max_tokens").asInt(1024));
    if (request.has("temperature")) {
      body.set("temperature", request.path("temperature"));
    }

    return webClient.post()
        .uri(URI.create(trimSlash(key.baseUrl()) + "/v1/messages"))
        .header("x-api-key", key.apiKey())
        .header("anthropic-version", "2023-06-01")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .retrieve()
        .toEntityFlux(String.class);
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
