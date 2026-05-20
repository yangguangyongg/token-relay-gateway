package com.tokenrelay.gateway.workspace;

import java.util.Locale;

public enum WorkspaceRole {
  OWNER,
  ADMIN,
  MEMBER;

  public static WorkspaceRole parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return MEMBER;
    }
    return WorkspaceRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
