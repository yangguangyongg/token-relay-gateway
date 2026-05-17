package com.tokenrelay.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenrelay.gateway.admin.AdminPrincipal;
import com.tokenrelay.gateway.admin.AdminRole;
import com.tokenrelay.gateway.service.AdminIpAllowlistService;
import com.tokenrelay.gateway.service.AdminJwtService;
import com.tokenrelay.gateway.service.GatewayException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class AdminSecurityWebFilter implements WebFilter {
  public static final String ADMIN_PRINCIPAL_ATTR = "adminPrincipal";
  private static final String LOGIN_PATH = "/api/admin/auth/login";

  private final AdminJwtService adminJwtService;
  private final AdminIpAllowlistService ipAllowlistService;
  private final ObjectMapper objectMapper;

  public AdminSecurityWebFilter(
      AdminJwtService adminJwtService,
      AdminIpAllowlistService ipAllowlistService,
      ObjectMapper objectMapper) {
    this.adminJwtService = adminJwtService;
    this.ipAllowlistService = ipAllowlistService;
    this.objectMapper = objectMapper;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (!path.startsWith("/api/admin")) {
      return chain.filter(exchange);
    }

    String clientIp = resolveClientIp(exchange);
    if (!ipAllowlistService.isAllowed(clientIp)) {
      return writeError(exchange, HttpStatus.FORBIDDEN, "admin_ip_not_allowed", "Admin access is not allowed from this IP");
    }

    if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
      return chain.filter(exchange);
    }

    if (LOGIN_PATH.equals(path)) {
      return chain.filter(exchange);
    }

    String bearerToken = extractBearerToken(exchange.getRequest().getHeaders());
    if (bearerToken == null) {
      return writeError(exchange, HttpStatus.UNAUTHORIZED, "admin_missing_token", "Authorization: Bearer <admin-jwt> is required");
    }

    AdminPrincipal principal;
    try {
      principal = adminJwtService.verify(bearerToken);
    } catch (GatewayException ex) {
      return writeError(exchange, HttpStatus.valueOf(ex.status()), ex.code(), ex.getMessage());
    } catch (RuntimeException ex) {
      return writeError(exchange, HttpStatus.UNAUTHORIZED, "admin_invalid_token", "Admin token is invalid");
    }

    AdminRole requiredRole = requiredRole(exchange.getRequest().getMethod());
    if (!principal.hasRole(requiredRole) && !(requiredRole == AdminRole.VIEWER && principal.hasRole(AdminRole.ADMIN))) {
      return writeError(exchange, HttpStatus.FORBIDDEN, "admin_forbidden", "Insufficient admin role");
    }

    exchange.getAttributes().put(ADMIN_PRINCIPAL_ATTR, principal);
    return chain.filter(exchange);
  }

  private AdminRole requiredRole(HttpMethod method) {
    return HttpMethod.GET.equals(method) ? AdminRole.VIEWER : AdminRole.ADMIN;
  }

  private String extractBearerToken(HttpHeaders headers) {
    String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return null;
    }
    String token = authorization.substring("Bearer ".length()).trim();
    return token.isEmpty() ? null : token;
  }

  private String resolveClientIp(ServerWebExchange exchange) {
    String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) {
      return stripPort(realIp.trim());
    }
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String[] parts = forwarded.split(",");
      String last = parts[parts.length - 1].trim();
      if (!last.isBlank()) {
        return stripPort(last);
      }
    }
    InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
    if (remoteAddress == null || remoteAddress.getAddress() == null) {
      return "";
    }
    return remoteAddress.getAddress().getHostAddress();
  }

  private String stripPort(String value) {
    if (value.startsWith("[") && value.contains("]")) {
      return value.substring(1, value.indexOf(']'));
    }
    int colonCount = value.length() - value.replace(":", "").length();
    if (colonCount == 1 && value.contains(".")) {
      return value.substring(0, value.indexOf(':'));
    }
    return value;
  }

  private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    String json;
    try {
      json = objectMapper.createObjectNode()
          .put("error", code)
          .put("message", message)
          .toString();
    } catch (Exception ex) {
      json = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}";
    }
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    return exchange.getResponse()
        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
  }
}
