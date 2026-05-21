package com.tokenrelay.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenrelay.gateway.domain.ProviderKey;
import com.tokenrelay.gateway.proxy.GatewayOperation;
import com.tokenrelay.gateway.proxy.GatewayRequest;
import com.tokenrelay.gateway.proxy.ProviderProtocol;
import com.tokenrelay.gateway.proxy.ProviderResponse;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
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
  public boolean supports(ProviderKey key, GatewayRequest request) {
    String model = request.routingBody().path("model").asText("");
    return "GEMINI".equalsIgnoreCase(key.provider()) || model.startsWith("gemini-");
  }

  @Override
  public Mono<ProviderResponse> execute(ProviderKey key, GatewayRequest request) {
    if (request.operation() == GatewayOperation.EMBEDDINGS) {
      return webClient.post()
          .uri(URI.create(trimSlash(key.baseUrl()) + "/v1beta/openai/embeddings"))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey())
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(BodyInserters.fromValue(request.embeddingsBody()))
          .retrieve()
          .toEntityFlux(String.class)
          .map(response -> new ProviderResponse(ProviderProtocol.EMBEDDINGS, response));
    }
    JsonNode body = withStreamUsageEnabled(request.chatCompletionsBody());
    return webClient.post()
        .uri(URI.create(trimSlash(key.baseUrl()) + "/v1beta/openai/chat/completions"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .retrieve()
        .toEntityFlux(String.class)
        .map(response -> new ProviderResponse(ProviderProtocol.CHAT_COMPLETIONS, response));
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
