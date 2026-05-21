package com.tokenrelay.gateway.proxy;

import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

public record ProviderResponse(
    ProviderProtocol protocol,
    ResponseEntity<Flux<String>> response) {}
