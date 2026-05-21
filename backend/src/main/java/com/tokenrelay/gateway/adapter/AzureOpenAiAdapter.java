package com.tokenrelay.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
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
public class AzureOpenAiAdapter implements ProviderAdapter {
  private final WebClient webClient;

  public AzureOpenAiAdapter(WebClient webClient) {
    this.webClient = webClient;
  }

  @Override
  public boolean supports(ProviderKey key, GatewayRequest request) {
    return "AZURE_OPENAI".equalsIgnoreCase(key.provider());
  }

  @Override
  public Mono<ProviderResponse> execute(ProviderKey key, GatewayRequest request) {
    String deployment = key.azureDeployment() == null || key.azureDeployment().isBlank()
        ? request.routingBody().path("model").asText()
        : key.azureDeployment();
    String resource = request.operation() == GatewayOperation.EMBEDDINGS ? "embeddings" : "chat/completions";
    JsonNode body = request.operation() == GatewayOperation.EMBEDDINGS ? request.embeddingsBody() : request.chatCompletionsBody();
    MediaType accept = request.operation() == GatewayOperation.EMBEDDINGS ? MediaType.APPLICATION_JSON : MediaType.TEXT_EVENT_STREAM;
    ProviderProtocol protocol = request.operation() == GatewayOperation.EMBEDDINGS
        ? ProviderProtocol.EMBEDDINGS
        : ProviderProtocol.CHAT_COMPLETIONS;
    String url = trimSlash(key.baseUrl())
        + "/openai/deployments/" + deployment + "/" + resource + "?api-version=2024-10-21";
    return webClient.post()
        .uri(URI.create(url))
        .header("api-key", key.apiKey())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(accept, MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .retrieve()
        .toEntityFlux(String.class)
        .map(response -> new ProviderResponse(protocol, response));
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
