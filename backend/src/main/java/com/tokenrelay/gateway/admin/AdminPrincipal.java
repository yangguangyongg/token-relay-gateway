package com.tokenrelay.gateway.admin;

import java.util.Set;

public record AdminPrincipal(String username, Set<AdminRole> roles) {
  public boolean hasRole(AdminRole role) {
    return roles != null && roles.contains(role);
  }
}
