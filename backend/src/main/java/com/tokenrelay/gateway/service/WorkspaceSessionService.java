package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.WorkspaceMembership;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.WorkspaceMembershipRepository;
import com.tokenrelay.gateway.workspace.WorkspacePrincipal;
import com.tokenrelay.gateway.workspace.WorkspaceRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class WorkspaceSessionService {
  private final WorkspaceJwtService workspaceJwtService;
  private final GatewayUserRepository users;
  private final WorkspaceMembershipRepository memberships;

  public WorkspaceSessionService(
      WorkspaceJwtService workspaceJwtService,
      GatewayUserRepository users,
      WorkspaceMembershipRepository memberships) {
    this.workspaceJwtService = workspaceJwtService;
    this.users = users;
    this.memberships = memberships;
  }

  public Mono<GatewayUser> authenticate(ServerWebExchange exchange) {
    String token = extractBearerToken(exchange);
    WorkspacePrincipal principal = workspaceJwtService.verify(token);
    return users.findById(principal.userId())
        .filter(user -> "ACTIVE".equalsIgnoreCase(user.status()))
        .switchIfEmpty(Mono.error(new GatewayException(401, "workspace_user_not_found", "Workspace user is not active")));
  }

  public Mono<WorkspaceMembership> requireWorkspaceRole(
      UUID workspaceId,
      UUID userId,
      Set<WorkspaceRole> allowedRoles) {
    return memberships.findByWorkspaceIdAndUserIdAndStatus(workspaceId, userId, "ACTIVE")
        .switchIfEmpty(Mono.error(new GatewayException(403, "workspace_forbidden", "You are not a member of this workspace")))
        .flatMap(membership -> {
          WorkspaceRole role;
          try {
            role = WorkspaceRole.parse(membership.role());
          } catch (IllegalArgumentException ex) {
            return Mono.error(new GatewayException(403, "workspace_forbidden", "Workspace role is invalid"));
          }
          if (!allowedRoles.contains(role)) {
            return Mono.error(new GatewayException(403, "workspace_forbidden", "Insufficient workspace role"));
          }
          return Mono.just(membership);
        });
  }

  private String extractBearerToken(ServerWebExchange exchange) {
    String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (auth == null || !auth.startsWith("Bearer ")) {
      throw new GatewayException(401, "workspace_missing_token", "Authorization: Bearer <workspace-jwt> is required");
    }
    String token = auth.substring("Bearer ".length()).trim();
    if (token.isEmpty()) {
      throw new GatewayException(401, "workspace_missing_token", "Workspace bearer token is empty");
    }
    return token;
  }
}
