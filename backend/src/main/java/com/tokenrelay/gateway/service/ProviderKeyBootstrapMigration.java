package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.repository.ProviderKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProviderKeyBootstrapMigration implements ApplicationRunner {
  private static final Logger logger = LoggerFactory.getLogger(ProviderKeyBootstrapMigration.class);

  private final ProviderKeyRepository providerKeys;
  private final ProviderKeySecurityService providerKeySecurity;

  public ProviderKeyBootstrapMigration(
      ProviderKeyRepository providerKeys,
      ProviderKeySecurityService providerKeySecurity) {
    this.providerKeys = providerKeys;
    this.providerKeySecurity = providerKeySecurity;
  }

  @Override
  public void run(ApplicationArguments args) {
    int migrated = providerKeys.findAll()
        .filter(key -> !providerKeySecurity.isEncrypted(key.apiKey()))
        .map(providerKeySecurity::encryptForStorage)
        .flatMap(providerKeys::save)
        .collectList()
        .map(list -> list.size())
        .blockOptional()
        .orElse(0);

    if (migrated > 0) {
      logger.info("Encrypted {} legacy provider API keys at startup", migrated);
    }
  }
}
