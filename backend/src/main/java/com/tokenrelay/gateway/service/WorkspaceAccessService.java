package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.Workspace;
import com.tokenrelay.gateway.domain.WorkspaceMembership;
import com.tokenrelay.gateway.repository.WorkspaceMembershipRepository;
import com.tokenrelay.gateway.repository.WorkspaceRepository;
import com.tokenrelay.gateway.workspace.WorkspaceRole;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class WorkspaceAccessService {
  private static final Set<WorkspaceRole> MANAGER_ROLES = Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

  private final WorkspaceRepository workspaces;
  private final WorkspaceMembershipRepository memberships;

  public WorkspaceAccessService(
      WorkspaceRepository workspaces,
      WorkspaceMembershipRepository memberships) {
    this.workspaces = workspaces;
    this.memberships = memberships;
  }

  public Mono<Workspace> requireActiveWorkspace(UUID workspaceId) {
    return workspaces.findById(workspaceId)
        .filter(workspace -> "ACTIVE".equalsIgnoreCase(workspace.status()))
        .switchIfEmpty(Mono.error(new GatewayException(404, "workspace_not_found", "Workspace not found")));
  }

  public Mono<WorkspaceMembership> requireManager(UUID workspaceId, UUID userId) {
    return memberships.findByWorkspaceIdAndUserIdAndStatus(workspaceId, userId, "ACTIVE")
        .switchIfEmpty(Mono.error(new GatewayException(403, "workspace_forbidden", "You are not a member of this workspace")))
        .flatMap(membership -> {
          WorkspaceRole role;
          try {
            role = WorkspaceRole.parse(membership.role());
          } catch (IllegalArgumentException ex) {
            return Mono.error(new GatewayException(403, "workspace_forbidden", "Workspace role is invalid"));
          }
          if (!MANAGER_ROLES.contains(role)) {
            return Mono.error(new GatewayException(403, "workspace_forbidden", "Owner/Admin role is required"));
          }
          return Mono.just(membership);
        });
  }

  public Workspace newWorkspace(String name, UUID createdByUserId) {
    String trimmedName = name == null ? "" : name.trim();
    if (trimmedName.isBlank()) {
      throw new GatewayException(400, "workspace_name_required", "workspace name is required");
    }
    String slug = slugify(trimmedName) + "-" + UUID.randomUUID().toString().substring(0, 8);
    return new Workspace(
        null,
        trimmedName,
        slug,
        "ACTIVE",
        createdByUserId,
        Instant.now(),
        Instant.now());
  }

  public WorkspaceMembership newMembership(UUID workspaceId, UUID userId, WorkspaceRole role) {
    return new WorkspaceMembership(
        null,
        workspaceId,
        userId,
        role.name(),
        "ACTIVE",
        Instant.now(),
        Instant.now());
  }

  public String normalizeWorkspaceRole(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new GatewayException(400, "invalid_workspace_role", "workspace role is required");
    }
    try {
      return WorkspaceRole.parse(raw).name();
    } catch (IllegalArgumentException ex) {
      throw new GatewayException(400, "invalid_workspace_role", "role must be OWNER/ADMIN/MEMBER");
    }
  }

  private String slugify(String input) {
    String normalized = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
    return normalized.isBlank() ? "workspace" : normalized;
  }
}
