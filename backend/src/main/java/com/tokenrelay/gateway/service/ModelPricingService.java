package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.domain.ModelPricing;
import com.tokenrelay.gateway.repository.ModelPricingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ModelPricingService {
  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

  private final ModelPricingRepository modelPricingRepository;

  public ModelPricingService(ModelPricingRepository modelPricingRepository) {
    this.modelPricingRepository = modelPricingRepository;
  }

  public Mono<PricingMatch> resolve(String provider, String model) {
    String normalizedProvider = normalize(provider);
    String normalizedModel = normalize(model);
    return modelPricingRepository.findByStatusOrderByEffectiveFromDesc("ACTIVE")
        .filter(row -> providerMatches(normalizedProvider, row.provider()))
        .map(row -> toCandidate(row, normalizedModel))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .sort(Comparator.comparingInt(PricingCandidate::score).reversed())
        .next()
        .map(candidate -> new PricingMatch(
            candidate.pricing().id(),
            candidate.pricing().currency(),
            candidate.pricing().promptPricePer1mTokens(),
            candidate.pricing().completionPricePer1mTokens()))
        .switchIfEmpty(Mono.just(PricingMatch.unmatched()));
  }

  public BigDecimal estimateCost(PricingMatch pricing, long promptTokens, long completionTokens) {
    if (!pricing.matched()) {
      return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }
    BigDecimal promptCost = pricing.promptPricePer1mTokens()
        .multiply(BigDecimal.valueOf(promptTokens))
        .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    BigDecimal completionCost = pricing.completionPricePer1mTokens()
        .multiply(BigDecimal.valueOf(completionTokens))
        .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    return promptCost.add(completionCost).setScale(8, RoundingMode.HALF_UP);
  }

  private Optional<PricingCandidate> toCandidate(ModelPricing pricing, String model) {
    String pattern = normalize(pricing.modelPattern());
    if (pattern.equals("*")) {
      return Optional.of(new PricingCandidate(pricing, 1));
    }
    if (pattern.endsWith("*")) {
      String prefix = pattern.substring(0, pattern.length() - 1);
      if (model.startsWith(prefix)) {
        return Optional.of(new PricingCandidate(pricing, 100 + prefix.length()));
      }
      return Optional.empty();
    }
    if (model.equals(pattern)) {
      return Optional.of(new PricingCandidate(pricing, 1000 + pattern.length()));
    }
    return Optional.empty();
  }

  private boolean providerMatches(String requestProvider, String rowProvider) {
    String normalizedRow = normalize(rowProvider);
    return normalizedRow.equals("*")
        || normalizedRow.equals("ANY")
        || normalizedRow.equals(requestProvider);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private record PricingCandidate(ModelPricing pricing, int score) {}

  public record PricingMatch(
      java.util.UUID pricingRuleId,
      String currency,
      BigDecimal promptPricePer1mTokens,
      BigDecimal completionPricePer1mTokens) {
    public boolean matched() {
      return pricingRuleId != null;
    }

    public static PricingMatch unmatched() {
      return new PricingMatch(null, "USD", BigDecimal.ZERO, BigDecimal.ZERO);
    }
  }
}
