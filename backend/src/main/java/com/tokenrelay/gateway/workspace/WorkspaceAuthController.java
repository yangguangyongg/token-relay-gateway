package com.tokenrelay.gateway.workspace;

import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.Workspace;
import com.tokenrelay.gateway.repository.GatewayUserRepository;
import com.tokenrelay.gateway.repository.WorkspaceMembershipRepository;
import com.tokenrelay.gateway.repository.WorkspaceRepository;
import com.tokenrelay.gateway.service.GatewayException;
import com.tokenrelay.gateway.service.PasswordHashService;
import com.tokenrelay.gateway.service.WorkspaceAccessService;
import com.tokenrelay.gateway.service.WorkspaceJwtService;
import com.tokenrelay.gateway.service.WorkspaceSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/workspace/auth")
public class WorkspaceAuthController {
  private final GatewayUserRepository users;
  private final WorkspaceRepository workspaces;
  private final WorkspaceMembershipRepository memberships;
  private final PasswordHashService passwordHashService;
  private final WorkspaceJwtService workspaceJwtService;
  private final WorkspaceSessionService workspaceSessionService;
  private final WorkspaceAccessService workspaceAccessService;
  private final DatabaseClient databaseClient;

  public WorkspaceAuthController(
      GatewayUserRepository users,
      WorkspaceRepository workspaces,
      WorkspaceMembershipRepository memberships,
      PasswordHashService passwordHashService,
      WorkspaceJwtService workspaceJwtService,
      WorkspaceSessionService workspaceSessionService,
      WorkspaceAccessService workspaceAccessService,
      DatabaseClient databaseClient) {
    this.users = users;
    this.workspaces = workspaces;
    this.memberships = memberships;
    this.passwordHashService = passwordHashService;
    this.workspaceJwtService = workspaceJwtService;
    this.workspaceSessionService = workspaceSessionService;
    this.workspaceAccessService = workspaceAccessService;
    this.databaseClient = databaseClient;
  }

  @PostMapping("/register")
  public Mono<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    String email = normalizeEmail(request.email());
    validatePasswordStrength(request.password());
    String displayName = normalizeDisplayName(request.displayName());
    String workspaceName = request.workspaceName() == null || request.workspaceName().isBlank()
        ? displayName + " Workspace"
        : request.workspaceName().trim();

    return users.findByEmail(email)
        .flatMap(existing -> Mono.<AuthResponse>error(new GatewayException(409, "email_taken", "Email already exists")))
        .switchIfEmpty(Mono.defer(() -> {
          Instant now = Instant.now();
          GatewayUser toCreate = new GatewayUser(
              null,
              email,
              displayName,
              "ACTIVE",
              request.monthlyTokenQuota() <= 0 ? 1_000_000L : request.monthlyTokenQuota(),
              passwordHashService.hash(request.password()),
              now,
              now);
          return users.save(toCreate)
              .flatMap(savedUser -> {
                Workspace workspace = workspaceAccessService.newWorkspace(
                    workspaceName,
                    savedUser.id(),
                    WorkspaceAccessService.WORKSPACE_TYPE_PERSONAL);
                return workspaces.save(workspace)
                    .flatMap(savedWorkspace -> memberships.save(
                        workspaceAccessService.newMembership(savedWorkspace.id(), savedUser.id(), WorkspaceRole.OWNER))
                        .then(ensureBillingPolicy(savedUser.id()))
                        .then(assembleAuthResponse(savedUser, List.of(savedWorkspace))));
              });
        }));
  }

  @PostMapping("/login")
  public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    String email = normalizeEmail(request.email());
    return users.findByEmail(email)
        .switchIfEmpty(Mono.error(new GatewayException(401, "invalid_credentials", "Email or password is incorrect")))
        .flatMap(user -> {
          if (!"ACTIVE".equalsIgnoreCase(user.status())) {
            return Mono.error(new GatewayException(403, "user_disabled", "User is disabled"));
          }
          if (user.passwordHash() == null || user.passwordHash().isBlank()) {
            return Mono.error(new GatewayException(401, "password_not_set", "Password login is not enabled for this account"));
          }
          if (!passwordHashService.matches(request.password(), user.passwordHash())) {
            return Mono.error(new GatewayException(401, "invalid_credentials", "Email or password is incorrect"));
          }
          return memberships.findByUserIdAndStatus(user.id(), "ACTIVE")
              .flatMap(membership -> workspaces.findById(membership.workspaceId())
                  .filter(workspace -> "ACTIVE".equalsIgnoreCase(workspace.status())))
              .collectList()
              .flatMap(userWorkspaces -> {
                if (userWorkspaces.isEmpty()) {
                  return Mono.error(new GatewayException(403, "workspace_missing", "No active workspace membership found"));
                }
                return assembleAuthResponse(user, userWorkspaces);
              });
        });
  }

  @GetMapping("/me")
  public Mono<AuthResponse> me(ServerWebExchange exchange) {
    return workspaceSessionService.authenticate(exchange)
        .flatMap(user -> memberships.findByUserIdAndStatus(user.id(), "ACTIVE")
            .flatMap(membership -> workspaces.findById(membership.workspaceId())
                .filter(workspace -> "ACTIVE".equalsIgnoreCase(workspace.status())))
            .collectList()
            .flatMap(userWorkspaces -> assembleAuthResponse(user, userWorkspaces)));
  }

  private Mono<AuthResponse> assembleAuthResponse(GatewayUser user, List<Workspace> userWorkspaces) {
    String token = workspaceJwtService.issueToken(user.id(), user.email());
    return memberships.findByUserIdAndStatus(user.id(), "ACTIVE")
        .collectList()
        .map(userMemberships -> {
          Map<UUID, String> rolesByWorkspace = userMemberships.stream()
              .collect(java.util.stream.Collectors.toMap(
                  m -> m.workspaceId(),
                  m -> m.role(),
                  (a, b) -> a));
          List<WorkspaceMembershipView> membershipsView = userWorkspaces.stream()
              .map(workspace -> new WorkspaceMembershipView(
                  workspace.id(),
                  workspace.name(),
                  workspace.slug(),
                  workspace.type(),
                  rolesByWorkspace.getOrDefault(workspace.id(), "MEMBER"),
                  workspace.status()))
              .toList();
          return new AuthResponse(
              token,
              "Bearer",
              user.id(),
              user.email(),
              user.displayName(),
              membershipsView);
        });
  }

  private Mono<Void> ensureBillingPolicy(UUID userId) {
    return databaseClient.sql("""
        INSERT INTO user_billing_policies (user_id, currency, monthly_budget_usd, alert_threshold_percent, auto_disable_api_keys, status)
        VALUES (:user_id, 'USD', 0, 80, false, 'ACTIVE')
        ON CONFLICT (user_id) DO NOTHING
        """)
        .bind("user_id", userId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private String normalizeEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new GatewayException(400, "invalid_email", "email is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeDisplayName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      throw new GatewayException(400, "invalid_display_name", "displayName is required");
    }
    return displayName.trim();
  }

  private void validatePasswordStrength(String password) {
    if (password == null || password.length() < 8) {
      throw new GatewayException(400, "weak_password", "password must be at least 8 characters");
    }
  }

  public record RegisterRequest(
      @Email String email,
      @NotBlank String password,
      @NotBlank String displayName,
      String workspaceName,
      long monthlyTokenQuota) {}

  public record LoginRequest(@Email String email, @NotBlank String password) {}

  public record WorkspaceMembershipView(
      UUID workspaceId,
      String workspaceName,
      String workspaceSlug,
      String workspaceType,
      String role,
      String workspaceStatus) {}

  public record AuthResponse(
      String accessToken,
      String tokenType,
      UUID userId,
      String email,
      String displayName,
      List<WorkspaceMembershipView> memberships) {}
}
