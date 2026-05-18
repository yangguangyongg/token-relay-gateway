package com.tokenrelay.gateway.repository;

import com.tokenrelay.gateway.domain.UserBillingPolicy;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface UserBillingPolicyRepository extends ReactiveCrudRepository<UserBillingPolicy, UUID> {}
