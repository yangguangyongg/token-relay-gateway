package com.tokenrelay.gateway.auth;

import com.tokenrelay.gateway.domain.ApiKeyRecord;
import com.tokenrelay.gateway.domain.GatewayUser;

public record AuthContext(GatewayUser user, ApiKeyRecord apiKey) {}
