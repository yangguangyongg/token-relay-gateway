package com.tokenrelay.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenrelay.gateway.admin.AdminPrincipal;
import com.tokenrelay.gateway.admin.AdminRole;
import com.tokenrelay.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class AdminJwtService {
  private static final String HMAC_ALG = "HmacSHA256";
  private static final String HEADER_ALG = "HS256";
  private static final String HEADER_TYP = "JWT";
  private static final String ISSUER = "token-relay-gateway";
  private static final Base64.Encoder B64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64_URL_DECODER = Base64.getUrlDecoder();

  private final ObjectMapper objectMapper;
  private final SecretKeySpec signingKey;
  private final GatewayProperties properties;

  public AdminJwtService(ObjectMapper objectMapper, GatewayProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.signingKey = new SecretKeySpec(resolveJwtSecret(properties.adminJwtSecret()), HMAC_ALG);
  }

  public String issueToken(AdminPrincipal principal) {
    try {
      long issuedAt = Instant.now().getEpochSecond();
      long expiresAt = issuedAt + properties.adminJwtTtl().toSeconds();

      Map<String, Object> header = Map.of("alg", HEADER_ALG, "typ", HEADER_TYP);
      Map<String, Object> payload = new HashMap<>();
      payload.put("iss", ISSUER);
      payload.put("sub", principal.username());
      payload.put("roles", principal.roles().stream().map(Enum::name).toList());
      payload.put("iat", issuedAt);
      payload.put("exp", expiresAt);

      String headerPart = encodeJson(header);
      String payloadPart = encodeJson(payload);
      String signingInput = headerPart + "." + payloadPart;
      String signaturePart = B64_URL_ENCODER.encodeToString(hmac(signingInput.getBytes(StandardCharsets.UTF_8)));
      return signingInput + "." + signaturePart;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to issue admin JWT", ex);
    }
  }

  public AdminPrincipal verify(String token) {
    if (token == null || token.isBlank()) {
      throw new GatewayException(401, "admin_missing_token", "Admin bearer token is required");
    }

    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token format is invalid");
    }

    String signingInput = parts[0] + "." + parts[1];
    byte[] expectedSignature = hmac(signingInput.getBytes(StandardCharsets.UTF_8));
    byte[] actualSignature;
    try {
      actualSignature = B64_URL_DECODER.decode(parts[2]);
    } catch (IllegalArgumentException ex) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token signature encoding is invalid");
    }

    if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token signature mismatch");
    }

    JsonNode header = decodeJson(parts[0], "header");
    if (!HEADER_ALG.equals(header.path("alg").asText()) || !HEADER_TYP.equals(header.path("typ").asText())) {
      throw new GatewayException(401, "admin_invalid_token", "Unsupported admin token header");
    }

    JsonNode payload = decodeJson(parts[1], "payload");
    if (!ISSUER.equals(payload.path("iss").asText())) {
      throw new GatewayException(401, "admin_invalid_token", "Unexpected admin token issuer");
    }

    long now = Instant.now().getEpochSecond();
    long exp = payload.path("exp").asLong(0);
    if (exp <= now) {
      throw new GatewayException(401, "admin_token_expired", "Admin token has expired");
    }

    String subject = payload.path("sub").asText();
    if (subject == null || subject.isBlank()) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token subject is missing");
    }

    Set<AdminRole> roles = parseRoles(payload.path("roles"));
    if (roles.isEmpty()) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token roles are missing");
    }

    return new AdminPrincipal(subject.toLowerCase(Locale.ROOT), roles);
  }

  private Set<AdminRole> parseRoles(JsonNode rolesNode) {
    if (rolesNode == null || !rolesNode.isArray()) {
      return Set.of();
    }
    EnumSet<AdminRole> roles = EnumSet.noneOf(AdminRole.class);
    List<String> invalidRoles = new ArrayList<>();
    for (JsonNode roleNode : rolesNode) {
      String role = roleNode.asText();
      if (role == null || role.isBlank()) {
        continue;
      }
      try {
        roles.add(AdminRole.valueOf(role.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        invalidRoles.add(role);
      }
    }
    if (!invalidRoles.isEmpty()) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token contains unknown role values");
    }
    return roles;
  }

  private String encodeJson(Object value) {
    try {
      return B64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize JWT payload", ex);
    }
  }

  private JsonNode decodeJson(String b64Url, String partName) {
    try {
      byte[] decoded = B64_URL_DECODER.decode(b64Url);
      return objectMapper.readTree(decoded);
    } catch (Exception ex) {
      throw new GatewayException(401, "admin_invalid_token", "Admin token " + partName + " is invalid JSON");
    }
  }

  private byte[] hmac(byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALG);
      mac.init(signingKey);
      return mac.doFinal(data);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to compute admin token signature", ex);
    }
  }

  private byte[] resolveJwtSecret(String raw) {
    if (raw == null || raw.isBlank() || "REPLACE_WITH_32_BYTE_BASE64_JWT_SECRET".equals(raw)) {
      throw new IllegalStateException("ADMIN_JWT_SECRET must be configured before startup");
    }

    byte[] maybeBase64;
    try {
      maybeBase64 = Base64.getDecoder().decode(raw);
      if (maybeBase64.length >= 32) {
        return maybeBase64;
      }
    } catch (IllegalArgumentException ignored) {
      // Fallback to raw UTF-8 bytes.
    }

    byte[] asUtf8 = raw.getBytes(StandardCharsets.UTF_8);
    if (asUtf8.length >= 32) {
      return asUtf8;
    }
    throw new IllegalStateException("ADMIN_JWT_SECRET must decode to at least 32 bytes");
  }
}
