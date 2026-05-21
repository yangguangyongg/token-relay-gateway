package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokenrelay.gateway.proxy.GatewayOperation;
import com.tokenrelay.gateway.proxy.GatewayRequest;
import com.tokenrelay.gateway.proxy.ProviderProtocol;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ProtocolCompatibilityService {
  private final ObjectMapper objectMapper;

  public ProtocolCompatibilityService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public MediaType responseContentType(GatewayRequest request) {
    if (request.operation() == GatewayOperation.EMBEDDINGS) {
      return MediaType.APPLICATION_JSON;
    }
    return request.stream() ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON;
  }

  public Flux<String> transformStreaming(ProviderProtocol upstream, GatewayRequest request, Flux<String> body) {
    if (!request.stream() || shouldPassthrough(upstream, request.operation())) {
      return body;
    }
    if (request.operation() == GatewayOperation.CHAT_COMPLETIONS && upstream == ProviderProtocol.ANTHROPIC_MESSAGES) {
      ChatCompletionsStreamTransformer transformer = new ChatCompletionsStreamTransformer();
      return body.concatMap(chunk -> Flux.fromIterable(transformer.fromAnthropic(chunk)));
    }
    if (request.operation() == GatewayOperation.ANTHROPIC_MESSAGES && upstream == ProviderProtocol.CHAT_COMPLETIONS) {
      AnthropicStreamTransformer transformer = new AnthropicStreamTransformer();
      return body.concatMap(chunk -> Flux.fromIterable(transformer.fromChat(chunk)));
    }
    if (request.operation() == GatewayOperation.RESPONSES && upstream == ProviderProtocol.CHAT_COMPLETIONS) {
      ResponsesStreamTransformer transformer = new ResponsesStreamTransformer();
      return body.concatMap(chunk -> Flux.fromIterable(transformer.fromChat(chunk)));
    }
    if (request.operation() == GatewayOperation.RESPONSES && upstream == ProviderProtocol.ANTHROPIC_MESSAGES) {
      ResponsesStreamTransformer transformer = new ResponsesStreamTransformer();
      return body.concatMap(chunk -> Flux.fromIterable(transformer.fromAnthropic(chunk)));
    }
    return body;
  }

  public boolean requiresRawStreaming(ProviderProtocol upstream, GatewayRequest request) {
    return request.stream() && !shouldPassthrough(upstream, request.operation());
  }

  public Object transformNonStreaming(ProviderProtocol upstream, GatewayRequest request, String bodyText) {
    if (bodyText == null || bodyText.isBlank()) {
      return objectMapper.createObjectNode();
    }
    if (shouldPassthrough(upstream, request.operation())) {
      return decodeJson(bodyText);
    }

    JsonNode root = parseJson(bodyText);
    return switch (request.operation()) {
      case CHAT_COMPLETIONS -> upstream == ProviderProtocol.ANTHROPIC_MESSAGES
          ? anthropicToChatCompletions(root)
          : root;
      case ANTHROPIC_MESSAGES -> upstream == ProviderProtocol.CHAT_COMPLETIONS
          ? chatCompletionsToAnthropic(root)
          : root;
      case RESPONSES -> upstream == ProviderProtocol.ANTHROPIC_MESSAGES
          ? anthropicToResponses(root)
          : chatCompletionsToResponses(root);
      case EMBEDDINGS -> root;
    };
  }

  private boolean shouldPassthrough(ProviderProtocol upstream, GatewayOperation operation) {
    return (operation == GatewayOperation.CHAT_COMPLETIONS && upstream == ProviderProtocol.CHAT_COMPLETIONS)
        || (operation == GatewayOperation.ANTHROPIC_MESSAGES && upstream == ProviderProtocol.ANTHROPIC_MESSAGES)
        || (operation == GatewayOperation.EMBEDDINGS && upstream == ProviderProtocol.EMBEDDINGS);
  }

  private Object decodeJson(String bodyText) {
    try {
      return objectMapper.readTree(bodyText);
    } catch (Exception ignored) {
      return bodyText;
    }
  }

  private JsonNode parseJson(String bodyText) {
    try {
      return objectMapper.readTree(bodyText);
    } catch (Exception error) {
      throw new GatewayException(502, "invalid_provider_response", "Provider returned an invalid JSON response");
    }
  }

  private JsonNode anthropicToChatCompletions(JsonNode root) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("id", root.path("id").asText("chatcmpl-" + Instant.now().toEpochMilli()));
    result.put("object", "chat.completion");
    result.put("created", Instant.now().getEpochSecond());
    result.put("model", root.path("model").asText(""));

    ObjectNode choice = objectMapper.createObjectNode();
    choice.put("index", 0);
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");
    message.put("content", flattenAnthropicContent(root.path("content")));
    choice.putNull("logprobs");
    choice.put("finish_reason", chatFinishReasonFromAnthropic(root.path("stop_reason").asText(null)));
    result.putArray("choices").add(choice);

    ObjectNode usage = result.putObject("usage");
    usage.put("prompt_tokens", root.path("usage").path("input_tokens").asLong(0));
    usage.put("completion_tokens", root.path("usage").path("output_tokens").asLong(0));
    usage.put("total_tokens", usage.path("prompt_tokens").asLong(0) + usage.path("completion_tokens").asLong(0));
    return result;
  }

  private JsonNode chatCompletionsToAnthropic(JsonNode root) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("id", root.path("id").asText("msg_" + Instant.now().toEpochMilli()));
    result.put("type", "message");
    result.put("role", "assistant");
    result.put("model", root.path("model").asText(""));

    ArrayNode content = result.putArray("content");
    content.add(objectMapper.createObjectNode()
        .put("type", "text")
        .put("text", extractChatAssistantText(root)));

    result.put("stop_reason", anthropicStopReasonFromChat(extractChatFinishReason(root)));
    result.putNull("stop_sequence");

    ObjectNode usage = result.putObject("usage");
    usage.put("input_tokens", root.path("usage").path("prompt_tokens").asLong(0));
    usage.put("output_tokens", root.path("usage").path("completion_tokens").asLong(0));
    return result;
  }

  private JsonNode chatCompletionsToResponses(JsonNode root) {
    ObjectNode response = responseShell(
        root.path("id").asText("resp_" + Instant.now().toEpochMilli()),
        root.path("model").asText(""),
        extractChatAssistantText(root),
        root.path("usage").path("prompt_tokens").asLong(0),
        root.path("usage").path("completion_tokens").asLong(0));
    response.put("status", "completed");
    return response;
  }

  private JsonNode anthropicToResponses(JsonNode root) {
    ObjectNode response = responseShell(
        root.path("id").asText("resp_" + Instant.now().toEpochMilli()),
        root.path("model").asText(""),
        flattenAnthropicContent(root.path("content")),
        root.path("usage").path("input_tokens").asLong(0),
        root.path("usage").path("output_tokens").asLong(0));
    response.put("status", "completed");
    return response;
  }

  private ObjectNode responseShell(String id, String model, String text, long inputTokens, long outputTokens) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("id", id);
    response.put("object", "response");
    response.put("created_at", Instant.now().getEpochSecond());
    response.put("model", model);
    response.put("status", "in_progress");

    ArrayNode output = response.putArray("output");
    ObjectNode message = output.addObject();
    message.put("id", "msg_" + id);
    message.put("type", "message");
    message.put("status", "completed");
    message.put("role", "assistant");
    ArrayNode content = message.putArray("content");
    ObjectNode textContent = objectMapper.createObjectNode();
    textContent.put("type", "output_text");
    textContent.put("text", text);
    textContent.set("annotations", objectMapper.createArrayNode());
    content.add(textContent);

    response.put("output_text", text);
    ObjectNode usage = response.putObject("usage");
    usage.put("input_tokens", inputTokens);
    usage.put("output_tokens", outputTokens);
    usage.put("total_tokens", inputTokens + outputTokens);
    return response;
  }

  private String extractChatAssistantText(JsonNode root) {
    JsonNode message = root.path("choices").path(0).path("message");
    String content = flattenTextNode(message.path("content"));
    if (!content.isBlank()) {
      return content;
    }
    return flattenTextNode(root.path("choices").path(0).path("text"));
  }

  private String extractChatFinishReason(JsonNode root) {
    return root.path("choices").path(0).path("finish_reason").asText(null);
  }

  private String flattenAnthropicContent(JsonNode content) {
    return flattenTextNode(content);
  }

  private String flattenTextNode(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText("");
    }
    if (node.isArray()) {
      StringBuilder builder = new StringBuilder();
      for (JsonNode child : node) {
        builder.append(flattenTextNode(child));
      }
      return builder.toString();
    }
    String type = node.path("type").asText("");
    if ("text".equalsIgnoreCase(type)
        || "output_text".equalsIgnoreCase(type)
        || "input_text".equalsIgnoreCase(type)
        || "text_delta".equalsIgnoreCase(type)) {
      return node.path("text").asText("");
    }
    if (node.has("content")) {
      String nested = flattenTextNode(node.path("content"));
      if (!nested.isBlank()) {
        return nested;
      }
    }
    return node.path("text").asText("");
  }

  private String anthropicStopReasonFromChat(String finishReason) {
    if (finishReason == null) {
      return "end_turn";
    }
    return switch (finishReason) {
      case "length" -> "max_tokens";
      case "tool_calls" -> "tool_use";
      default -> "end_turn";
    };
  }

  private String chatFinishReasonFromAnthropic(String stopReason) {
    if (stopReason == null) {
      return "stop";
    }
    return switch (stopReason) {
      case "max_tokens" -> "length";
      case "tool_use" -> "tool_calls";
      default -> "stop";
    };
  }

  private List<SseEvent> parseSseEvents(String chunk) {
    List<SseEvent> events = new ArrayList<>();
    if (chunk == null || chunk.isBlank()) {
      return events;
    }
    String normalized = chunk.replace("\r\n", "\n");
    for (String block : normalized.split("\n\n")) {
      if (block.isBlank()) {
        continue;
      }
      String eventName = null;
      StringBuilder data = new StringBuilder();
      boolean structured = false;
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          eventName = line.substring("event:".length()).trim();
          structured = true;
        } else if (line.startsWith("data:")) {
          if (data.length() > 0) {
            data.append('\n');
          }
          data.append(line.substring("data:".length()).trim());
          structured = true;
        }
      }
      if (data.length() > 0) {
        events.add(new SseEvent(eventName, data.toString()));
      } else if (!structured) {
        events.add(new SseEvent(null, block.trim()));
      }
    }
    return events;
  }

  private String chatChunk(JsonNode payload) {
    return "data:" + payload.toString() + "\n\n";
  }

  private String doneChunk() {
    return "data:[DONE]\n\n";
  }

  private String sse(String event, JsonNode payload) {
    return "event: " + event + "\ndata: " + payload.toString() + "\n\n";
  }

  private final class ChatCompletionsStreamTransformer {
    private String id = "chatcmpl-" + Instant.now().toEpochMilli();
    private String model = "";
    private long promptTokens = 0;
    private long completionTokens = 0;
    private String finishReason = "stop";
    private boolean started;

    List<String> fromAnthropic(String chunk) {
      List<String> output = new ArrayList<>();
      for (SseEvent event : parseSseEvents(chunk)) {
        if ("[DONE]".equals(event.data())) {
          output.add(doneChunk());
          continue;
        }
        JsonNode payload = parseJson(event.data());
        String eventName = anthropicEventName(event, payload);
        if ("message_start".equals(eventName)) {
          JsonNode message = payload.path("message");
          id = message.path("id").asText(id);
          model = message.path("model").asText(model);
          promptTokens = message.path("usage").path("input_tokens").asLong(0);
          output.add(chatChunk(chatDeltaPayload(id, model, "")));
          started = true;
          continue;
        }
        if ("content_block_delta".equals(eventName)) {
          String text = payload.path("delta").path("text").asText("");
          if (!text.isEmpty()) {
            output.add(chatChunk(chatDeltaPayload(id, model, text)));
          }
          continue;
        }
        if ("message_delta".equals(eventName)) {
          finishReason = chatFinishReasonFromAnthropic(payload.path("delta").path("stop_reason").asText(null));
          completionTokens = payload.path("usage").path("output_tokens").asLong(completionTokens);
          continue;
        }
        if ("message_stop".equals(eventName)) {
          if (!started) {
            output.add(chatChunk(chatDeltaPayload(id, model, "")));
          }
          output.add(chatChunk(chatFinishPayload(id, model, finishReason)));
          output.add(chatChunk(chatUsagePayload(id, model, promptTokens, completionTokens)));
          output.add(doneChunk());
        }
      }
      return output;
    }
  }

  private final class AnthropicStreamTransformer {
    private String id = "msg_" + Instant.now().toEpochMilli();
    private String model = "";
    private long promptTokens = 0;
    private long completionTokens = 0;
    private String finishReason = "end_turn";
    private boolean started;

    List<String> fromChat(String chunk) {
      List<String> output = new ArrayList<>();
      for (SseEvent event : parseSseEvents(chunk)) {
        if ("[DONE]".equals(event.data())) {
          output.add(sse("message_delta", anthropicMessageDelta(finishReason, completionTokens)));
          output.add(sse("content_block_stop", objectMapper.createObjectNode().put("type", "content_block_stop").put("index", 0)));
          output.add(sse("message_stop", objectMapper.createObjectNode().put("type", "message_stop")));
          continue;
        }
        JsonNode payload = parseJson(event.data());
        id = payload.path("id").asText(id);
        model = payload.path("model").asText(model);
        String deltaText = payload.path("choices").path(0).path("delta").path("content").asText("");
        if (!started) {
          output.add(sse("message_start", anthropicMessageStart(id, model)));
          output.add(sse("content_block_start", anthropicContentBlockStart()));
          started = true;
        }
        if (!deltaText.isEmpty()) {
          output.add(sse("content_block_delta", anthropicTextDelta(deltaText)));
        }
        String nextFinishReason = payload.path("choices").path(0).path("finish_reason").asText("");
        if (!nextFinishReason.isBlank() && !"null".equalsIgnoreCase(nextFinishReason)) {
          finishReason = anthropicStopReasonFromChat(nextFinishReason);
        }
        JsonNode usage = payload.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
          promptTokens = usage.path("prompt_tokens").asLong(promptTokens);
          completionTokens = usage.path("completion_tokens").asLong(completionTokens);
        }
      }
      return output;
    }
  }

  private final class ResponsesStreamTransformer {
    private String responseId = "resp_" + Instant.now().toEpochMilli();
    private String model = "";
    private long inputTokens = 0;
    private long outputTokens = 0;
    private StringBuilder text = new StringBuilder();
    private boolean started;

    List<String> fromChat(String chunk) {
      List<String> output = new ArrayList<>();
      for (SseEvent event : parseSseEvents(chunk)) {
        if ("[DONE]".equals(event.data())) {
          output.add(sse("response.output_text.done", responseTextDone(text.toString())));
          ObjectNode completed = responseShell(responseId, model, text.toString(), inputTokens, outputTokens);
          completed.put("status", "completed");
          output.add(sse("response.completed", completed));
          continue;
        }
        JsonNode payload = parseJson(event.data());
        responseId = payload.path("id").asText(responseId);
        model = payload.path("model").asText(model);
        if (!started) {
          output.add(sse("response.created", responseShell(responseId, model, "", 0, 0)));
          started = true;
        }
        String deltaText = payload.path("choices").path(0).path("delta").path("content").asText("");
        if (!deltaText.isEmpty()) {
          text.append(deltaText);
          output.add(sse("response.output_text.delta", responseTextDelta(deltaText)));
        }
        JsonNode usage = payload.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
          inputTokens = usage.path("prompt_tokens").asLong(inputTokens);
          outputTokens = usage.path("completion_tokens").asLong(outputTokens);
        }
      }
      return output;
    }

    List<String> fromAnthropic(String chunk) {
      List<String> output = new ArrayList<>();
      for (SseEvent event : parseSseEvents(chunk)) {
        if ("[DONE]".equals(event.data())) {
          output.add(sse("response.output_text.done", responseTextDone(text.toString())));
          ObjectNode completed = responseShell(responseId, model, text.toString(), inputTokens, outputTokens);
          completed.put("status", "completed");
          output.add(sse("response.completed", completed));
          continue;
        }
        JsonNode payload = parseJson(event.data());
        String eventName = anthropicEventName(event, payload);
        if ("message_start".equals(eventName)) {
          JsonNode message = payload.path("message");
          responseId = message.path("id").asText(responseId);
          model = message.path("model").asText(model);
          inputTokens = message.path("usage").path("input_tokens").asLong(inputTokens);
          output.add(sse("response.created", responseShell(responseId, model, "", inputTokens, 0)));
          started = true;
          continue;
        }
        if ("content_block_delta".equals(eventName)) {
          String deltaText = payload.path("delta").path("text").asText("");
          if (!deltaText.isEmpty()) {
            text.append(deltaText);
            output.add(sse("response.output_text.delta", responseTextDelta(deltaText)));
          }
          continue;
        }
        if ("message_delta".equals(eventName)) {
          outputTokens = payload.path("usage").path("output_tokens").asLong(outputTokens);
          continue;
        }
        if ("message_stop".equals(eventName)) {
          output.add(sse("response.output_text.done", responseTextDone(text.toString())));
          ObjectNode completed = responseShell(responseId, model, text.toString(), inputTokens, outputTokens);
          completed.put("status", "completed");
          output.add(sse("response.completed", completed));
        }
      }
      return output;
    }
  }

  private ObjectNode chatDeltaPayload(String id, String model, String content) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("id", id);
    payload.put("object", "chat.completion.chunk");
    payload.put("created", Instant.now().getEpochSecond());
    payload.put("model", model);
    ArrayNode choices = payload.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    ObjectNode delta = choice.putObject("delta");
    if (content.isEmpty()) {
      delta.put("role", "assistant");
      delta.put("content", "");
    } else {
      delta.put("content", content);
    }
    choice.putNull("logprobs");
    choice.putNull("finish_reason");
    return payload;
  }

  private ObjectNode chatFinishPayload(String id, String model, String finishReason) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("id", id);
    payload.put("object", "chat.completion.chunk");
    payload.put("created", Instant.now().getEpochSecond());
    payload.put("model", model);
    ArrayNode choices = payload.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    choice.putObject("delta");
    choice.putNull("logprobs");
    choice.put("finish_reason", finishReason);
    return payload;
  }

  private ObjectNode chatUsagePayload(String id, String model, long promptTokens, long completionTokens) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("id", id);
    payload.put("object", "chat.completion.chunk");
    payload.put("created", Instant.now().getEpochSecond());
    payload.put("model", model);
    payload.putArray("choices");
    ObjectNode usage = payload.putObject("usage");
    usage.put("prompt_tokens", promptTokens);
    usage.put("completion_tokens", completionTokens);
    usage.put("total_tokens", promptTokens + completionTokens);
    return payload;
  }

  private ObjectNode anthropicMessageStart(String id, String model) {
    ObjectNode event = objectMapper.createObjectNode();
    event.put("type", "message_start");
    ObjectNode message = event.putObject("message");
    message.put("id", id);
    message.put("type", "message");
    message.put("role", "assistant");
    message.put("model", model);
    message.putArray("content");
    message.putNull("stop_reason");
    message.putNull("stop_sequence");
    ObjectNode usage = message.putObject("usage");
    usage.put("input_tokens", promptTokensPlaceholder());
    usage.put("output_tokens", 0);
    return event;
  }

  private long promptTokensPlaceholder() {
    return 0;
  }

  private ObjectNode anthropicContentBlockStart() {
    ObjectNode event = objectMapper.createObjectNode();
    event.put("type", "content_block_start");
    event.put("index", 0);
    ObjectNode block = objectMapper.createObjectNode();
    block.put("type", "text");
    block.put("text", "");
    event.set("content_block", block);
    return event;
  }

  private ObjectNode anthropicTextDelta(String text) {
    ObjectNode event = objectMapper.createObjectNode();
    event.put("type", "content_block_delta");
    event.put("index", 0);
    ObjectNode delta = objectMapper.createObjectNode();
    delta.put("type", "text_delta");
    delta.put("text", text);
    event.set("delta", delta);
    return event;
  }

  private ObjectNode anthropicMessageDelta(String stopReason, long outputTokens) {
    ObjectNode event = objectMapper.createObjectNode();
    event.put("type", "message_delta");
    ObjectNode delta = objectMapper.createObjectNode();
    delta.put("stop_reason", stopReason);
    delta.putNull("stop_sequence");
    event.set("delta", delta);
    ObjectNode usage = objectMapper.createObjectNode();
    usage.put("output_tokens", outputTokens);
    event.set("usage", usage);
    return event;
  }

  private ObjectNode responseTextDelta(String delta) {
    return objectMapper.createObjectNode()
        .put("type", "response.output_text.delta")
        .put("delta", delta);
  }

  private ObjectNode responseTextDone(String text) {
    return objectMapper.createObjectNode()
        .put("type", "response.output_text.done")
        .put("text", text);
  }

  private String anthropicEventName(SseEvent event, JsonNode payload) {
    if (event.event() != null && !event.event().isBlank()) {
      return event.event();
    }
    return payload.path("type").asText("");
  }

  private record SseEvent(String event, String data) {}
}
