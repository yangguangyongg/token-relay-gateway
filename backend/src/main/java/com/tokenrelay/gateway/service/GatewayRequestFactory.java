package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenrelay.gateway.proxy.GatewayOperation;
import com.tokenrelay.gateway.proxy.GatewayRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GatewayRequestFactory {
  private final ObjectMapper objectMapper;

  public GatewayRequestFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public GatewayRequest chatCompletions(JsonNode request) {
    JsonNode chatBody = copyOrOriginal(request);
    JsonNode anthropicBody = toAnthropicMessagesBody(chatBody);
    return new GatewayRequest(
        GatewayOperation.CHAT_COMPLETIONS,
        request,
        chatBody,
        chatBody,
        anthropicBody,
        null);
  }

  public GatewayRequest responses(JsonNode request) {
    JsonNode chatBody = toChatCompletionsBodyFromResponses(request);
    JsonNode anthropicBody = toAnthropicMessagesBody(chatBody);
    return new GatewayRequest(
        GatewayOperation.RESPONSES,
        request,
        chatBody,
        chatBody,
        anthropicBody,
        null);
  }

  public GatewayRequest embeddings(JsonNode request) {
    JsonNode embeddingsBody = copyOrOriginal(request);
    return new GatewayRequest(
        GatewayOperation.EMBEDDINGS,
        request,
        embeddingsBody,
        null,
        null,
        embeddingsBody);
  }

  public GatewayRequest anthropicMessages(JsonNode request) {
    JsonNode anthropicBody = copyOrOriginal(request);
    JsonNode chatBody = toChatCompletionsBodyFromAnthropic(request);
    return new GatewayRequest(
        GatewayOperation.ANTHROPIC_MESSAGES,
        request,
        anthropicBody,
        chatBody,
        anthropicBody,
        null);
  }

  private JsonNode toChatCompletionsBodyFromResponses(JsonNode request) {
    if (!request.isObject()) {
      throw new GatewayException(400, "invalid_request", "Responses API request must be a JSON object");
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.set("model", requiredNode(request, "model", "model is required"));
    body.put("stream", request.path("stream").asBoolean(false));
    if (request.has("temperature")) {
      body.set("temperature", request.path("temperature"));
    }
    if (request.has("top_p")) {
      body.set("top_p", request.path("top_p"));
    }
    if (request.has("tools")) {
      body.set("tools", request.path("tools"));
    }
    if (request.has("tool_choice")) {
      body.set("tool_choice", request.path("tool_choice"));
    }
    if (request.has("metadata")) {
      body.set("metadata", request.path("metadata"));
    }
    if (request.has("max_output_tokens")) {
      body.set("max_tokens", request.path("max_output_tokens"));
    }

    ArrayNode messages = body.putArray("messages");
    appendSystemInstruction(messages, request.path("instructions"));
    appendResponsesInput(messages, request.path("input"));
    if (messages.isEmpty()) {
      throw new GatewayException(400, "input_required", "Responses API input is required");
    }
    return body;
  }

  private JsonNode toChatCompletionsBodyFromAnthropic(JsonNode request) {
    if (!request.isObject()) {
      throw new GatewayException(400, "invalid_request", "Anthropic message request must be a JSON object");
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.set("model", requiredNode(request, "model", "model is required"));
    body.put("stream", request.path("stream").asBoolean(false));
    body.set("max_tokens", requiredNode(request, "max_tokens", "max_tokens is required"));
    if (request.has("temperature")) {
      body.set("temperature", request.path("temperature"));
    }
    if (request.has("top_p")) {
      body.set("top_p", request.path("top_p"));
    }

    ArrayNode messages = body.putArray("messages");
    appendAnthropicSystem(messages, request.path("system"));
    JsonNode sourceMessages = request.path("messages");
    if (!sourceMessages.isArray() || sourceMessages.isEmpty()) {
      throw new GatewayException(400, "messages_required", "messages is required");
    }
    for (JsonNode message : sourceMessages) {
      ObjectNode nextMessage = objectMapper.createObjectNode();
      nextMessage.put("role", message.path("role").asText("user"));
      nextMessage.put("content", flattenContentText(message.path("content")));
      messages.add(nextMessage);
    }
    return body;
  }

  private JsonNode toAnthropicMessagesBody(JsonNode chatRequest) {
    if (chatRequest == null || !chatRequest.isObject()) {
      return null;
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.set("model", requiredNode(chatRequest, "model", "model is required"));
    body.put("stream", chatRequest.path("stream").asBoolean(false));
    body.put("max_tokens", chatRequest.path("max_tokens").asInt(1024));
    if (chatRequest.has("temperature")) {
      body.set("temperature", chatRequest.path("temperature"));
    }
    if (chatRequest.has("top_p")) {
      body.set("top_p", chatRequest.path("top_p"));
    }
    if (chatRequest.has("metadata")) {
      body.set("metadata", chatRequest.path("metadata"));
    }

    ArrayNode anthropicMessages = body.putArray("messages");
    List<String> systemParts = new ArrayList<>();
    JsonNode messages = chatRequest.path("messages");
    if (messages.isArray()) {
      for (JsonNode message : messages) {
        String role = message.path("role").asText("user");
        String text = flattenContentText(message.path("content"));
        if ("system".equalsIgnoreCase(role)) {
          if (!text.isBlank()) {
            systemParts.add(text);
          }
          continue;
        }
        ObjectNode anthropicMessage = objectMapper.createObjectNode();
        anthropicMessage.put("role", role);
        ArrayNode content = anthropicMessage.putArray("content");
        content.add(objectMapper.createObjectNode()
            .put("type", "text")
            .put("text", text));
        anthropicMessages.add(anthropicMessage);
      }
    }
    if (!systemParts.isEmpty()) {
      body.put("system", String.join("\n\n", systemParts));
    }
    return body;
  }

  private void appendResponsesInput(ArrayNode messages, JsonNode input) {
    if (input == null || input.isMissingNode() || input.isNull()) {
      return;
    }
    if (input.isTextual()) {
      messages.add(messageNode("user", input.asText()));
      return;
    }
    if (input.isArray()) {
      for (JsonNode item : input) {
        appendResponsesItem(messages, item);
      }
      return;
    }
    appendResponsesItem(messages, input);
  }

  private void appendResponsesItem(ArrayNode messages, JsonNode item) {
    if (item == null || item.isMissingNode() || item.isNull()) {
      return;
    }
    if (item.isTextual()) {
      messages.add(messageNode("user", item.asText()));
      return;
    }
    String role = item.path("role").asText("");
    if (!role.isBlank()) {
      messages.add(messageNode(role, flattenContentText(item.path("content"))));
      return;
    }
    String type = item.path("type").asText("");
    if ("input_text".equalsIgnoreCase(type) || "text".equalsIgnoreCase(type)) {
      messages.add(messageNode("user", item.path("text").asText("")));
      return;
    }
    String flattened = flattenContentText(item);
    if (!flattened.isBlank()) {
      messages.add(messageNode("user", flattened));
    }
  }

  private void appendSystemInstruction(ArrayNode messages, JsonNode instructions) {
    if (instructions == null || instructions.isMissingNode() || instructions.isNull()) {
      return;
    }
    String text = flattenContentText(instructions);
    if (!text.isBlank()) {
      messages.add(messageNode("system", text));
    }
  }

  private void appendAnthropicSystem(ArrayNode messages, JsonNode system) {
    if (system == null || system.isMissingNode() || system.isNull()) {
      return;
    }
    String text = flattenContentText(system);
    if (!text.isBlank()) {
      messages.add(messageNode("system", text));
    }
  }

  private ObjectNode messageNode(String role, String text) {
    return objectMapper.createObjectNode()
        .put("role", role)
        .put("content", text == null ? "" : text);
  }

  private JsonNode requiredNode(JsonNode request, String field, String message) {
    JsonNode node = request.path(field);
    if (node.isMissingNode() || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
      throw new GatewayException(400, "invalid_request", message);
    }
    return node;
  }

  private JsonNode copyOrOriginal(JsonNode node) {
    if (node == null) {
      return null;
    }
    return node.isObject() ? ((ObjectNode) node).deepCopy() : node;
  }

  private String flattenContentText(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText("");
    }
    if (node.isArray()) {
      List<String> parts = new ArrayList<>();
      for (JsonNode item : node) {
        String part = flattenContentText(item);
        if (!part.isBlank()) {
          parts.add(part);
        }
      }
      return String.join("", parts);
    }
    String type = node.path("type").asText("");
    if ("input_text".equalsIgnoreCase(type)
        || "output_text".equalsIgnoreCase(type)
        || "text".equalsIgnoreCase(type)
        || "text_delta".equalsIgnoreCase(type)) {
      return node.path("text").asText("");
    }
    if (node.has("content")) {
      String nested = flattenContentText(node.path("content"));
      if (!nested.isBlank()) {
        return nested;
      }
    }
    return node.path("text").asText("");
  }
}
