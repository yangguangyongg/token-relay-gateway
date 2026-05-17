package com.tokenrelay.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
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
public class OpenAiAdapter implements ProviderAdapter {
  private final WebClient webClient;

  public OpenAiAdapter(WebClient webClient) {
    this.webClient = webClient;
  }

  @Override
  public boolean supports(ProviderKey key, JsonNode request) {
    return "OPENAI".equalsIgnoreCase(key.provider());
  }

  @Override
  public Mono<ResponseEntity<Flux<String>>> stream(ProviderKey key, JsonNode request) {
    return webClient.post()
        .uri(URI.create(trimSlash(key.baseUrl()) + "/v1/chat/completions"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(request))
        .retrieve()
        .toEntityFlux(String.class);
  }

  protected String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
