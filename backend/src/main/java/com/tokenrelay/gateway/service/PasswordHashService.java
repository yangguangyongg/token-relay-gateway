package com.tokenrelay.gateway.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordHashService {
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public String hash(String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      throw new GatewayException(400, "invalid_password", "Password cannot be empty");
    }
    return encoder.encode(plaintext);
  }

  public boolean matches(String plaintext, String hash) {
    if (plaintext == null || hash == null || hash.isBlank()) {
      return false;
    }
    return encoder.matches(plaintext, hash);
  }
}
