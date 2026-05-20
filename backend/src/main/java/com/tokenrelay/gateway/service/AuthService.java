package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.auth.AuthContext;
import com.tokenrelay.gateway.repository.ApiKeyRepository;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.WorkspaceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class AuthService {
  private final ApiKeyRepository apiKeys;
  private final GatewayUserRepository users;
  private final WorkspaceRepository workspaces;
  private final HashService hashService;

  public AuthService(
      ApiKeyRepository apiKeys,
      GatewayUserRepository users,
      WorkspaceRepository workspaces,
      HashService hashService) {
    this.apiKeys = apiKeys;
    this.users = users;
    this.workspaces = workspaces;
    this.hashService = hashService;
  }

  public Mono<AuthContext> authenticate(ServerWebExchange exchange) {
    String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (auth == null || !auth.startsWith("Bearer ")) {
      return Mono.error(new GatewayException(401, "missing_bearer_token", "Authorization: Bearer <gateway-api-key> is required"));
    }

    String token = auth.substring("Bearer ".length()).trim();
    return apiKeys.findByKeyHash(hashService.sha256(token))
        .switchIfEmpty(Mono.error(new GatewayException(401, "invalid_api_key", "Gateway API key is invalid")))
        .flatMap(apiKey -> {
          if (!"ACTIVE".equalsIgnoreCase(apiKey.status())) {
            return Mono.error(new GatewayException(403, "api_key_disabled", "Gateway API key is disabled"));
          }
          if (apiKey.workspaceId() == null) {
            return Mono.error(new GatewayException(500, "api_key_workspace_missing", "API key workspace is not configured"));
          }
          return users.findById(apiKey.userId())
              .filter(user -> "ACTIVE".equalsIgnoreCase(user.status()))
              .switchIfEmpty(Mono.error(new GatewayException(403, "user_disabled", "User is disabled")))
              .flatMap(user -> workspaces.findById(apiKey.workspaceId())
                  .filter(workspace -> "ACTIVE".equalsIgnoreCase(workspace.status()))
                  .switchIfEmpty(Mono.error(new GatewayException(403, "workspace_disabled", "Workspace is disabled")))
                  .map(workspace -> new AuthContext(user, apiKey, workspace)));
        });
  }
}
