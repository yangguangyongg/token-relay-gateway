package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.config.GatewayProperties;
import com.tokenrelay.gateway.domain.ProviderKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class ProviderKeySecurityService {
  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private static final String PREFIX = "enc:v1";
  private static final byte[] AAD = "provider-key".getBytes(StandardCharsets.UTF_8);

  private final SecretKeySpec keySpec;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
  private final Base64.Decoder decoder = Base64.getUrlDecoder();

  public ProviderKeySecurityService(GatewayProperties properties) {
    this.keySpec = new SecretKeySpec(resolveKey(properties.providerKeyEncryptionKey()), "AES");
  }

  public ProviderKey encryptForStorage(ProviderKey key) {
    return key.withApiKey(encrypt(key.apiKey()));
  }

  public ProviderKey decryptForRuntime(ProviderKey key) {
    return key.withApiKey(decrypt(key.apiKey()));
  }

  public boolean isEncrypted(String value) {
    return value != null && value.startsWith(PREFIX + ":");
  }

  public String mask(String value) {
    if (value == null || value.isBlank()) {
      return "hidden";
    }
    if (value.length() <= 8) {
      return "****";
    }
    return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
  }

  public String maskStored(String storedValue) {
    return mask(decrypt(storedValue));
  }

  private String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      throw new GatewayException(400, "invalid_provider_key", "Provider API key cannot be empty");
    }
    if (isEncrypted(plaintext)) {
      return plaintext;
    }

    try {
      byte[] nonce = new byte[NONCE_BYTES];
      secureRandom.nextBytes(nonce);

      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      cipher.updateAAD(AAD);
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      return PREFIX + ":" + encoder.encodeToString(nonce) + ":" + encoder.encodeToString(ciphertext);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to encrypt provider API key", ex);
    }
  }

  private String decrypt(String storedValue) {
    if (storedValue == null || storedValue.isBlank()) {
      throw new GatewayException(500, "provider_key_missing", "Provider API key is missing");
    }
    if (!isEncrypted(storedValue)) {
      return storedValue;
    }

    String[] parts = storedValue.split(":");
    if (parts.length != 4) {
      throw new GatewayException(500, "provider_key_corrupted", "Stored provider API key format is invalid");
    }

    try {
      byte[] nonce = decoder.decode(parts[2]);
      byte[] ciphertext = decoder.decode(parts[3]);

      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      cipher.updateAAD(AAD);
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new GatewayException(500, "provider_key_decrypt_failed", "Failed to decrypt provider API key");
    }
  }

  private byte[] resolveKey(String raw) {
    if (raw == null || raw.isBlank() || "REPLACE_WITH_32_BYTE_BASE64_KEY".equals(raw)) {
      throw new IllegalStateException("PROVIDER_KEY_ENCRYPTION_KEY must be configured before startup");
    }

    byte[] maybeBase64;
    try {
      maybeBase64 = Base64.getDecoder().decode(raw);
      if (isSupportedAesKeySize(maybeBase64.length)) {
        return maybeBase64;
      }
    } catch (IllegalArgumentException ignored) {
      // Fallback to treating input as raw text.
    }

    byte[] asUtf8 = raw.getBytes(StandardCharsets.UTF_8);
    if (isSupportedAesKeySize(asUtf8.length)) {
      return asUtf8;
    }
    throw new IllegalStateException("PROVIDER_KEY_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes");
  }

  private boolean isSupportedAesKeySize(int size) {
    return size == 16 || size == 24 || size == 32;
  }
}
