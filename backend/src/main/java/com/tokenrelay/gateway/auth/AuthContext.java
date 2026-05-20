package com.tokenrelay.gateway.auth;

import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.domain.GatewayUser;
import com.tokenrelay.gateway.domain.Workspace;

public record AuthContext(GatewayUser user, ApiKeyRecord apiKey, Workspace workspace) {}
