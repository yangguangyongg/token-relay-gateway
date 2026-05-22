package com.tokenrelay.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebConfig {
  @Bean
  WebClient webClient(WebClient.Builder builder, GatewayProperties properties) {
    ConnectionProvider connectionProvider = ConnectionProvider.builder("provider-http-pool")
        .maxConnections(properties.providerHttpMaxConnectionsSafe())
        .pendingAcquireMaxCount(properties.providerHttpPendingAcquireMaxCountSafe())
        .pendingAcquireTimeout(properties.providerHttpPendingAcquireTimeout())
        .maxIdleTime(properties.providerHttpIdleTimeout())
        .build();

    long timeoutSeconds = Math.max(1, properties.providerTimeout().toSeconds());
    HttpClient httpClient = HttpClient.create(connectionProvider)
        .compress(true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.providerHttpConnectTimeoutMillisSafe())
        .responseTimeout(properties.providerTimeout())
        .doOnConnected(connection -> connection
            .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
            .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

    return builder.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
  }

  @Bean
  CorsWebFilter corsWebFilter(GatewayProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.corsAllowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of(HttpHeaders.CONTENT_TYPE));
    config.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsWebFilter(source);
  }
}
