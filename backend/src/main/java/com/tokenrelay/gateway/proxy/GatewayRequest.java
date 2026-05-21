package com.tokenrelay.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;

public record GatewayRequest(
    GatewayOperation operation,
    JsonNode originalBody,
    JsonNode routingBody,
    JsonNode chatCompletionsBody,
    JsonNode anthropicMessagesBody,
    JsonNode embeddingsBody) {

  public boolean stream() {
    return originalBody.path("stream").asBoolean(false);
  }
}
