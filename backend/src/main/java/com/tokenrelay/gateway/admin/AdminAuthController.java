package com.tokenrelay.gateway.admin;

import com.tokenrelay.gateway.config.AdminSecurityWebFilter;
import com.tokenrelay.gateway.config.GatewayProperties;
import com.tokenrelay.gateway.service.AdminJwtService;
import com.tokenrelay.gateway.service.AdminLoginService;
import com.tokenrelay.gateway.service.AuditService;
import com.tokenrelay.gateway.service.GatewayException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
  private final AdminLoginService loginService;
  private final AdminJwtService jwtService;
  private final GatewayProperties properties;
  private final AuditService auditService;

  public AdminAuthController(
      AdminLoginService loginService,
      AdminJwtService jwtService,
      GatewayProperties properties,
      AuditService auditService) {
    this.loginService = loginService;
    this.jwtService = jwtService;
    this.properties = properties;
    this.auditService = auditService;
  }

  @PostMapping("/login")
  public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    AdminPrincipal principal = loginService.authenticate(request.username(), request.password())
        .orElseThrow(() -> new GatewayException(401, "admin_login_failed", "Invalid admin username or password"));
    String token = jwtService.issueToken(principal);
    return auditService.log(principal.username(), "ADMIN_LOGIN", "admin", "role=" + principal.roles())
        .thenReturn(new LoginResponse(
            token,
            "Bearer",
            properties.adminJwtTtl().toSeconds(),
            principal.username(),
            principal.roles()));
  }

  @GetMapping("/me")
  public CurrentAdmin me(ServerWebExchange exchange) {
    AdminPrincipal principal = exchange.getAttribute(AdminSecurityWebFilter.ADMIN_PRINCIPAL_ATTR);
    if (principal == null) {
      throw new GatewayException(401, "admin_missing_context", "Admin auth context is missing");
    }
    return new CurrentAdmin(principal.username(), principal.roles());
  }

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  public record LoginResponse(
      String accessToken,
      String tokenType,
      long expiresInSeconds,
      String username,
      Set<AdminRole> roles) {}

  public record CurrentAdmin(String username, Set<AdminRole> roles) {}
}
