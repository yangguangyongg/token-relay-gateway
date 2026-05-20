package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenrelay.gateway.config.GatewayProperties;
import com.tokenrelay.gateway.workspace.WorkspacePrincipal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceJwtService {
  private static final String HMAC_ALG = "HmacSHA256";
  private static final String HEADER_ALG = "HS256";
  private static final String HEADER_TYP = "JWT";
  private static final String ISSUER = "token-relay-workspace";
  private static final Base64.Encoder B64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64_URL_DECODER = Base64.getUrlDecoder();

  private final ObjectMapper objectMapper;
  private final SecretKeySpec signingKey;
  private final GatewayProperties properties;

  public WorkspaceJwtService(ObjectMapper objectMapper, GatewayProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.signingKey = new SecretKeySpec(resolveJwtSecret(properties), HMAC_ALG);
  }

  public String issueToken(UUID userId, String email) {
    try {
      long issuedAt = Instant.now().getEpochSecond();
      long expiresAt = issuedAt + properties.workspaceJwtTtl().toSeconds();

      Map<String, Object> header = Map.of("alg", HEADER_ALG, "typ", HEADER_TYP);
      Map<String, Object> payload = new HashMap<>();
      payload.put("iss", ISSUER);
      payload.put("sub", userId.toString());
      payload.put("email", email);
      payload.put("iat", issuedAt);
      payload.put("exp", expiresAt);

      String headerPart = encodeJson(header);
      String payloadPart = encodeJson(payload);
      String signingInput = headerPart + "." + payloadPart;
      String signaturePart = B64_URL_ENCODER.encodeToString(hmac(signingInput.getBytes(StandardCharsets.UTF_8)));
      return signingInput + "." + signaturePart;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to issue workspace JWT", ex);
    }
  }

  public WorkspacePrincipal verify(String token) {
    if (token == null || token.isBlank()) {
      throw new GatewayException(401, "workspace_missing_token", "Workspace bearer token is required");
    }

    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new GatewayException(401, "workspace_invalid_token", "Workspace token format is invalid");
    }

    String signingInput = parts[0] + "." + parts[1];
    byte[] expectedSignature = hmac(signingInput.getBytes(StandardCharsets.UTF_8));
    byte[] actualSignature;
    try {
      actualSignature = B64_URL_DECODER.decode(parts[2]);
    } catch (IllegalArgumentException ex) {
      throw new GatewayException(401, "workspace_invalid_token", "Workspace token signature encoding is invalid");
    }

    if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
      throw new GatewayException(401, "workspace_invalid_token", "Workspace token signature mismatch");
    }

    JsonNode header = decodeJson(parts[0], "header");
    if (!HEADER_ALG.equals(header.path("alg").asText()) || !HEADER_TYP.equals(header.path("typ").asText())) {
      throw new GatewayException(401, "workspace_invalid_token", "Unsupported workspace token header");
    }

    JsonNode payload = decodeJson(parts[1], "payload");
    if (!ISSUER.equals(payload.path("iss").asText())) {
      throw new GatewayException(401, "workspace_invalid_token", "Unexpected workspace token issuer");
    }

    long now = Instant.now().getEpochSecond();
    long exp = payload.path("exp").asLong(0);
    if (exp <= now) {
      throw new GatewayException(401, "workspace_token_expired", "Workspace token has expired");
    }

    UUID userId;
    try {
      userId = UUID.fromString(payload.path("sub").asText());
    } catch (RuntimeException ex) {
      throw new GatewayException(401, "workspace_invalid_token", "Workspace token subject is invalid");
    }
    String email = payload.path("email").asText("");
    return new WorkspacePrincipal(userId, email);
  }

  private String encodeJson(Object value) {
    try {
      return B64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize workspace JWT payload", ex);
    }
  }

  private JsonNode decodeJson(String b64Url, String partName) {
    try {
      byte[] decoded = B64_URL_DECODER.decode(b64Url);
      return objectMapper.readTree(decoded);
    } catch (Exception ex) {
      throw new GatewayException(401, "workspace_invalid_token", "Workspace token " + partName + " is invalid JSON");
    }
  }

  private byte[] hmac(byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALG);
      mac.init(signingKey);
      return mac.doFinal(data);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to compute workspace token signature", ex);
    }
  }

  private byte[] resolveJwtSecret(GatewayProperties properties) {
    String raw = properties.workspaceJwtSecret();
    if (raw == null || raw.isBlank() || "REPLACE_WITH_32_BYTE_BASE64_WORKSPACE_JWT_SECRET".equals(raw)) {
      raw = properties.adminJwtSecret();
    }
    if (raw == null || raw.isBlank() || "REPLACE_WITH_32_BYTE_BASE64_JWT_SECRET".equals(raw)) {
      throw new IllegalStateException("WORKSPACE_JWT_SECRET or ADMIN_JWT_SECRET must be configured before startup");
    }

    byte[] maybeBase64;
    try {
      maybeBase64 = Base64.getDecoder().decode(raw);
      if (maybeBase64.length >= 32) {
        return maybeBase64;
      }
    } catch (IllegalArgumentException ignored) {
      // fallback to raw utf8
    }

    byte[] asUtf8 = raw.getBytes(StandardCharsets.UTF_8);
    if (asUtf8.length >= 32) {
      return asUtf8;
    }
    throw new IllegalStateException("WORKSPACE_JWT_SECRET must decode to at least 32 bytes");
  }
}
