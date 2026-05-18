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
public class GeminiAdapter implements ProviderAdapter {
  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  public GeminiAdapter(WebClient webClient, ObjectMapper objectMapper) {
    this.webClient = webClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean supports(ProviderKey key, JsonNode request) {
    String model = request.path("model").asText("");
    return "GEMINI".equalsIgnoreCase(key.provider()) || model.startsWith("gemini-");
  }

  @Override
  public Mono<ResponseEntity<Flux<String>>> stream(ProviderKey key, JsonNode request) {
    JsonNode body = withStreamUsageEnabled(request);
    return webClient.post()
        .uri(URI.create(trimSlash(key.baseUrl()) + "/v1beta/openai/chat/completions"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .retrieve()
        .toEntityFlux(String.class);
  }

  private JsonNode withStreamUsageEnabled(JsonNode request) {
    if (!request.path("stream").asBoolean(false) || !request.isObject()) {
      return request;
    }
    ObjectNode body = ((ObjectNode) request).deepCopy();
    ObjectNode streamOptions;
    if (body.path("stream_options").isObject()) {
      streamOptions = (ObjectNode) body.path("stream_options");
    } else {
      streamOptions = objectMapper.createObjectNode();
      body.set("stream_options", streamOptions);
    }
    streamOptions.put("include_usage", true);
    return body;
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
