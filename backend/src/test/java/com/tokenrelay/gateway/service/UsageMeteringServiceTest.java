package com.tokenrelay.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UsageMeteringServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private UsageMeteringService service;

  @BeforeEach
  void setUp() {
    service = new UsageMeteringService(objectMapper);
  }

  @Test
  void usesProviderUsageWhenStreamingOpenAiIncludesUsageChunk() {
    UsageMeteringService.UsageTracker tracker = service.newTracker("OPENAI", request(true));

    tracker.onChunk("data: {\"choices\":[{\"delta\":{\"content\":\"relay\"}}]}");
    tracker.onChunk("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}");

    UsageMeteringService.UsageSnapshot snapshot = tracker.snapshot();
    assertThat(snapshot.promptTokens()).isEqualTo(11);
    assertThat(snapshot.completionTokens()).isEqualTo(7);
    assertThat(snapshot.totalTokens()).isEqualTo(18);
    assertThat(snapshot.fromProviderUsage()).isTrue();
  }

  @Test
  void estimatesCompletionTokensFromStreamingTextWhenProviderUsageIsMissing() {
    UsageMeteringService.UsageTracker tracker = service.newTracker("OPENAI", request(true));

    tracker.onChunk("event: message\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}");
    tracker.onChunk("data: {\"choices\":[{\"delta\":{\"content\":\"world from relay\"}}]}");

    UsageMeteringService.UsageSnapshot snapshot = tracker.snapshot();
    assertThat(snapshot.promptTokens()).isGreaterThan(0);
    assertThat(snapshot.completionTokens()).isEqualTo("Hello world from relay".length() / 4L);
    assertThat(snapshot.totalTokens()).isEqualTo(snapshot.promptTokens() + snapshot.completionTokens());
    assertThat(snapshot.fromProviderUsage()).isFalse();
  }

  @Test
  void estimatesAnthropicStreamingOutputFromTextDelta() {
    UsageMeteringService.UsageTracker tracker = service.newTracker("ANTHROPIC", request(true));

    tracker.onChunk("event: content_block_delta\ndata: {\"delta\":{\"type\":\"text_delta\",\"text\":\"Partial anthropic output\"}}");

    UsageMeteringService.UsageSnapshot snapshot = tracker.snapshot();
    assertThat(snapshot.completionTokens()).isEqualTo("Partial anthropic output".length() / 4L);
    assertThat(snapshot.totalTokens()).isEqualTo(snapshot.promptTokens() + snapshot.completionTokens());
  }

  private ObjectNode request(boolean stream) {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("model", "gpt-4o-mini");
    request.put("stream", stream);
    request.putArray("messages")
        .add(objectMapper.createObjectNode().put("role", "user").put("content", "Say relay-ok"));
    return request;
  }
}
