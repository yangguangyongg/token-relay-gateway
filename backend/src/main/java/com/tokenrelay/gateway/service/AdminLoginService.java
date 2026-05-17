package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.admin.AdminPrincipal;
import com.tokenrelay.gateway.admin.AdminRole;
import com.tokenrelay.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AdminLoginService {
  private final GatewayProperties properties;

  public AdminLoginService(GatewayProperties properties) {
    this.properties = properties;
  }

  public Optional<AdminPrincipal> authenticate(String username, String password) {
    if (isBlank(username) || isBlank(password)) {
      return Optional.empty();
    }

    String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
    String adminUsername = defaultIfBlank(properties.adminBootstrapAdminUsername(), "admin")
        .trim()
        .toLowerCase(Locale.ROOT);
    String adminPassword = defaultIfBlank(properties.adminBootstrapAdminPassword(), properties.adminApiKey());
    if (constantTimeEquals(normalizedUsername, adminUsername) && constantTimeEquals(password, adminPassword)) {
      return Optional.of(new AdminPrincipal(adminUsername, Set.of(AdminRole.ADMIN, AdminRole.VIEWER)));
    }

    String viewerUsername = defaultIfBlank(properties.adminBootstrapViewerUsername(), "viewer")
        .trim()
        .toLowerCase(Locale.ROOT);
    String viewerPassword = defaultIfBlank(properties.adminBootstrapViewerPassword(), "");
    if (!viewerPassword.isBlank()
        && constantTimeEquals(normalizedUsername, viewerUsername)
        && constantTimeEquals(password, viewerPassword)) {
      return Optional.of(new AdminPrincipal(viewerUsername, Set.of(AdminRole.VIEWER)));
    }

    return Optional.empty();
  }

  private boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private String defaultIfBlank(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
