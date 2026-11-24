package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionService;
import com.vitadelta.api.entitlement.SubscriptionService.Activation;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual-grant adapter of the billing port (section 8.9): an administrator activates PRO for {@code
 * durationDays} (source {@code MANUAL}) or extends the current PRO period, in one transaction that
 * locks the user's row first.
 */
@Component
public class ManualGrantBillingProvider implements BillingProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ManualGrantBillingProvider.class);

  private final UserRepository users;
  private final SubscriptionService subscriptions;
  private final TransactionTemplate transactions;

  public ManualGrantBillingProvider(
      UserRepository users,
      SubscriptionService subscriptions,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.subscriptions = subscriptions;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Grants the plan of an {@link ActivationRequest.ManualGrant} request.
   *
   * @throws ResponseStatusException 404 when the user does not exist
   * @throws com.vitadelta.api.common.problem.ProblemException {@code 409 conflict_retry}
   */
  @Override
  public ActivationResult activate(ActivationRequest request) {
    if (!(request instanceof ActivationRequest.ManualGrant grant)) {
      throw new IllegalArgumentException("not a manual grant: " + request);
    }
    if (grant.planCode() != PlanCode.PRO) {
      throw new IllegalArgumentException("only PRO can be granted");
    }
    ActivationResult result =
        transactions.execute(status -> {
          if (users.findByIdForUpdate(grant.userId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
          }
          Activation activation =
              subscriptions.activate(
                  grant.userId(), PlanCode.PRO, grant.durationDays(), SubscriptionSource.MANUAL);
          return new ActivationResult(
              PlanCode.PRO, activation.subscription(), activation.extended());
        });
    LOG.info(
        "Manual grant of {} for {} days to user {} by {} ({})",
        grant.planCode(),
        grant.durationDays(),
        grant.userId(),
        grant.grantedBy(),
        result.extended() ? "extended" : "activated");
    return result;
  }
}
