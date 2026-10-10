package org.project.mechanic_shop.application.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the Saga waits at each step before giving up and compensating.
 *
 * @param budgetGenerationTimeout the Billing Service has to answer the budget request
 * @param approvalTimeout the customer has to approve or reject the budget
 * @param paymentTimeout the customer has to pay after approving
 */
@ConfigurationProperties(prefix = "app.saga")
public record SagaProperties(Duration budgetGenerationTimeout, Duration approvalTimeout, Duration paymentTimeout) {}
