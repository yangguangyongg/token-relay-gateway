package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class UsageMeteringService {
  private final ObjectMapper objectMapper;

  public UsageMeteringService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public UsageTracker newTracker(String provider, JsonNode request) {
    return new UsageTracker(normalize(provider), request, objectMapper);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public record UsageSnapshot(long promptTokens, long completionTokens, long totalTokens, boolean fromProviderUsage) {}

  public static class UsageTracker {
    private final String provider;
    private final JsonNode request;
    private final ObjectMapper objectMapper;
    private final StringBuilder rawBody = new StringBuilder();
    private long promptTokens = -1;
    private long completionTokens = -1;
    private long totalTokens = -1;

    UsageTracker(String provider, JsonNode request, ObjectMapper objectMapper) {
      this.provider = provider;
      this.request = request;
      this.objectMapper = objectMapper;
    }

    public void onChunk(String chunk) {
      if (chunk == null) {
        return;
      }
      rawBody.append(chunk).append('\n');
      captureUsageFromTextChunk(chunk);
    }

    public UsageSnapshot snapshot() {
      // Fallback parse for non-stream responses where raw body is a full JSON object.
      captureUsageFromJsonText(rawBody.toString());

      long prompt = promptTokens >= 0 ? promptTokens : estimatePromptTokens(request);
      long completion = completionTokens >= 0 ? completionTokens : 0;
      long total = totalTokens >= 0 ? totalTokens : prompt + completion;
      boolean fromProvider = promptTokens >= 0 || completionTokens >= 0 || totalTokens >= 0;
      return new UsageSnapshot(prompt, completion, total, fromProvider);
    }

    private void captureUsageFromTextChunk(String text) {
      if (text == null || text.isBlank()) {
        return;
      }
      String trimmed = text.trim();
      if (trimmed.startsWith("data:")) {
        String payload = trimmed.substring("data:".length()).trim();
        if (!payload.isEmpty() && !payload.equals("[DONE]")) {
          captureUsageFromJsonText(payload);
        }
        return;
      }
      if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        captureUsageFromJsonText(trimmed);
      }
    }

    private void captureUsageFromJsonText(String jsonText) {
      if (jsonText == null || jsonText.isBlank()) {
        return;
      }
      try {
        JsonNode root = objectMapper.readTree(jsonText);
        captureUsageFromNode(root);
      } catch (Exception ignored) {
        // Not a complete JSON node yet.
      }
    }

    private void captureUsageFromNode(JsonNode root) {
      JsonNode usage = root.path("usage");
      if (!usage.isMissingNode() && !usage.isNull()) {
        mergeOpenAiUsage(usage);
        mergeAnthropicUsage(usage);
      }

      if ("ANTHROPIC".equals(provider)) {
        JsonNode messageUsage = root.path("message").path("usage");
        if (!messageUsage.isMissingNode() && !messageUsage.isNull()) {
          mergeAnthropicUsage(messageUsage);
        }
        JsonNode deltaUsage = root.path("delta").path("usage");
        if (!deltaUsage.isMissingNode() && !deltaUsage.isNull()) {
          mergeAnthropicUsage(deltaUsage);
        }
      }

      JsonNode choices = root.path("choices");
      if (choices instanceof ArrayNode array && !array.isEmpty()) {
        JsonNode first = array.get(0);
        JsonNode choiceUsage = first.path("usage");
        if (!choiceUsage.isMissingNode() && !choiceUsage.isNull()) {
          mergeOpenAiUsage(choiceUsage);
        }
      }
    }

    private void mergeOpenAiUsage(JsonNode usage) {
      long prompt = usage.path("prompt_tokens").asLong(-1);
      long completion = usage.path("completion_tokens").asLong(-1);
      long total = usage.path("total_tokens").asLong(-1);
      if (prompt >= 0) {
        promptTokens = Math.max(promptTokens, prompt);
      }
      if (completion >= 0) {
        completionTokens = Math.max(completionTokens, completion);
      }
      if (total >= 0) {
        totalTokens = Math.max(totalTokens, total);
      }
    }

    private void mergeAnthropicUsage(JsonNode usage) {
      long inputTokens = usage.path("input_tokens").asLong(-1);
      long outputTokens = usage.path("output_tokens").asLong(-1);
      if (inputTokens >= 0) {
        promptTokens = Math.max(promptTokens, inputTokens);
      }
      if (outputTokens >= 0) {
        completionTokens = Math.max(completionTokens, outputTokens);
      }
      if (promptTokens >= 0 && completionTokens >= 0) {
        totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
      }
    }

    private long estimatePromptTokens(JsonNode request) {
      JsonNode messages = request.path("messages");
      if (messages.isMissingNode() || !messages.isArray()) {
        return 1;
      }
      return Math.max(1, messages.toString().length() / 4L);
    }
  }
}
