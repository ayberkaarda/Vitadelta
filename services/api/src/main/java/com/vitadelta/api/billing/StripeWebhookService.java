package com.vitadelta.api.billing;

import com.vitadelta.api.billing.BillingAccountRepository.SubscriptionState;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.Subscription;
import com.vitadelta.api.entitlement.SubscriptionRepository;
import com.vitadelta.api.entitlement.SubscriptionService;
import com.vitadelta.api.entitlement.SubscriptionService.ProviderApplication;
import com.vitadelta.api.entitlement.SubscriptionService.ProviderChange;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles one Stripe webhook delivery (ADR-0014).
 *
 * <ol>
 *   <li>The raw body is verified with {@link StripeSignatureVerifier} before it is parsed; any
 *       failure, and a body that is not an event, is a generic {@code 400} whose reason is only
 *       logged and counted.
 *   <li>Events of the other mode (live or test) and unhandled types are acknowledged and ignored.
 *       Handled: {@value #CHECKOUT_COMPLETED}, {@code invoice.paid}, {@code
 *       invoice.payment_failed}, {@code customer.subscription.updated} and {@code
 *       customer.subscription.deleted}.
 *   <li>A repeated event id is acknowledged without effect.
 *   <li>The referenced subscription is read from Stripe ({@link StripeApi#retrieveSubscription}),
 *       outside any transaction: the event payload is only used to find it, so a late or reordered
 *       delivery always applies the current state. A Stripe failure is {@code 503} (Stripe retries
 *       the delivery).
 *   <li>The subscription must carry the {@code user_id} metadata this API set at checkout (for
 *       {@value #CHECKOUT_COMPLETED} it must equal the session's {@code client_reference_id}) and
 *       the configured price; otherwise the event is ignored. E-mail addresses are never used.
 *   <li>In one transaction that first locks the user's row: the event id is checked again and
 *       recorded (a customer or subscription already mapped to another user is refused), the state
 *       of this subscription is merged forward ({@link BillingAccountRepository#mergeSubscription};
 *       a period counts as paid only when the status is active or trialing and the latest invoice
 *       is paid, so past due, unpaid and open renewals change nothing), and the user's entitlement
 *       is reconciled with all of the user's provider subscriptions ({@link #reconcile}): PRO
 *       follows the live subscription paid furthest ahead, through the billing port ({@link
 *       StripeBillingProvider}), and ends only when no live subscription pays for it. The billing
 *       portal follows the customer of the entitled subscription ({@link #mapCustomer}).
 *   <li>A user with more than one live subscription keeps the entitled one; the others are canceled
 *       at Stripe after the transaction, and the event is recorded only once that succeeded (a
 *       failure answers {@code 503}, so the redelivery retries it).
 *   <li>When the user no longer exists, a subscription that has not ended is canceled at Stripe
 *       after the transaction (a checkout paid after the account was deleted, or a deletion that
 *       committed during this delivery), so a deleted account is never left being charged.
 * </ol>
 *
 * Metrics: {@code vitadelta.billing.webhook{outcome}}, {@code
 * vitadelta.billing.webhook.rejected{reason}}, {@code vitadelta.billing.payment_failed}, {@code
 * vitadelta.billing.surplus.detected}, {@code vitadelta.billing.stripe.failed}, {@code
 * vitadelta.billing.cancel_on_delete.canceled}, {@code vitadelta.billing.cancel_on_delete.failed}.
 */
@Service
public class StripeWebhookService {

  static final String CHECKOUT_COMPLETED = "checkout.session.completed";
  static final String PAYMENT_FAILED = "invoice.payment_failed";

  /** The event types that are applied. */
  static final Set<String> HANDLED =
      Set.of(
          CHECKOUT_COMPLETED,
          "invoice.paid",
          PAYMENT_FAILED,
          "customer.subscription.updated",
          "customer.subscription.deleted");

  private static final Pattern EVENT_ID = Pattern.compile("^evt_[A-Za-z0-9_]{1,250}$");
  private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z0-9_.]{1,100}$");

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookService.class);

  /** What became of a delivery. */
  public enum Outcome {
    /** The subscription changed. */
    APPLIED,
    /** Applied, but the state was already current. */
    UNCHANGED,
    /** The event id was already applied. */
    DUPLICATE,
    /** Not a handled event type. */
    IGNORED_TYPE,
    /** An event or subscription of the other mode (live or test). */
    IGNORED_MODE,
    /** Not a subscription created by this API's checkout (no or mismatching user, other price). */
    IGNORED_FOREIGN,
    /** The user no longer exists; a live subscription of the user is canceled at Stripe. */
    USER_MISSING,
    /** The customer or subscription is already mapped to another user. */
    CUSTOMER_CONFLICT;

    /** Metric tag and stored outcome. */
    public String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The parsed parts of a verified event. */
  record Event(String id, String type, boolean livemode, JsonNode object) {}

  /** What an event refers to. */
  record Reference(String subscriptionId, UUID clientReference) {}

  private final BillingProperties properties;
  private final StripeApi stripe;
  private final BillingAccountRepository accounts;
  private final StripeBillingProvider provider;
  private final SubscriptionService subscriptions;
  private final SubscriptionRepository activeSubscriptions;
  private final UserRepository users;
  private final MeterRegistry meters;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final StripeSignatureVerifier verifier;

  public StripeWebhookService(
      BillingProperties properties,
      StripeApi stripe,
      BillingAccountRepository accounts,
      StripeBillingProvider provider,
      SubscriptionService subscriptions,
      SubscriptionRepository activeSubscriptions,
      UserRepository users,
      MeterRegistry meters,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.properties = properties;
    this.stripe = stripe;
    this.accounts = accounts;
    this.provider = provider;
    this.subscriptions = subscriptions;
    this.activeSubscriptions = activeSubscriptions;
    this.users = users;
    this.meters = meters;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.verifier =
        properties.enabled()
            ? new StripeSignatureVerifier(
                properties.stripe().webhookSecret(), properties.webhookTolerance(), clock)
            : null;
  }

  /**
   * Verifies and applies one delivery.
   *
   * @param signatureHeaders every value of the {@code Stripe-Signature} header
   * @throws ResponseStatusException {@code 400} for an unauthentic or unreadable request
   * @throws ProblemException {@code 503 billing_unavailable} without a provider or when Stripe
   *     cannot be read
   */
  public Outcome handle(byte[] payload, List<String> signatureHeaders) {
    if (verifier == null) {
      throw ProviderCheckoutService.unavailable();
    }
    try {
      verifier.verify(payload, signatureHeaders);
    } catch (StripeSignatureVerifier.Rejected e) {
      throw reject(e.failure().tag());
    }
    Event event = parse(payload).orElseThrow(() -> reject("malformed_body"));
    Outcome outcome = process(event);
    meters.counter("vitadelta.billing.webhook", "outcome", outcome.tag()).increment();
    LOG.info("Stripe event {} ({}): {}", event.id(), event.type(), outcome.tag());
    return outcome;
  }

  private ResponseStatusException reject(String reason) {
    meters.counter("vitadelta.billing.webhook.rejected", "reason", reason).increment();
    LOG.warn("Stripe webhook rejected ({})", reason);
    return new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  /** The event fields, or empty when the body is not a Stripe event. */
  static Optional<Event> parse(byte[] payload) {
    JsonNode root;
    try {
      root = JSON.readTree(payload);
    } catch (JacksonException e) {
      return Optional.empty();
    }
    if (root == null || !root.isObject()) {
      return Optional.empty();
    }
    JsonNode id = root.get("id");
    JsonNode type = root.get("type");
    JsonNode livemode = root.get("livemode");
    JsonNode data = root.get("data");
    JsonNode object = data == null ? null : data.get("object");
    if (id == null
        || !id.isString()
        || !EVENT_ID.matcher(id.stringValue()).matches()
        || type == null
        || !type.isString()
        || !EVENT_TYPE.matcher(type.stringValue()).matches()
        || livemode == null
        || !livemode.isBoolean()
        || object == null
        || !object.isObject()) {
      return Optional.empty();
    }
    return Optional.of(
        new Event(id.stringValue(), type.stringValue(), livemode.booleanValue(), object));
  }

  private Outcome process(Event event) {
    boolean live = properties.stripe().liveMode();
    if (event.livemode() != live) {
      return Outcome.IGNORED_MODE;
    }
    if (!HANDLED.contains(event.type())) {
      return Outcome.IGNORED_TYPE;
    }
    if (event.type().equals(PAYMENT_FAILED)) {
      meters.counter("vitadelta.billing.payment_failed").increment();
    }
    if (accounts.eventRecorded(event.id())) {
      return Outcome.DUPLICATE;
    }
    Optional<Reference> reference = reference(event);
    if (reference.isEmpty()) {
      return Outcome.IGNORED_FOREIGN;
    }
    StripeSubscription subscription;
    try {
      subscription = stripe.retrieveSubscription(reference.get().subscriptionId());
    } catch (StripeApiException e) {
      if (e.notFound()) {
        LOG.warn("Stripe event {} refers to a subscription Stripe does not know", event.id());
        return Outcome.IGNORED_FOREIGN;
      }
      meters
          .counter(
              "vitadelta.billing.stripe.failed",
              "operation",
              "retrieve_subscription",
              "reason",
              e.reason().tag())
          .increment();
      LOG.warn("Stripe event {} not applied: {}", event.id(), e.getMessage());
      throw ProviderCheckoutService.unavailable();
    }
    if (subscription.livemode() != live) {
      return Outcome.IGNORED_MODE;
    }
    Optional<UUID> userId = owner(event, reference.get(), subscription);
    if (userId.isEmpty()) {
      return Outcome.IGNORED_FOREIGN;
    }
    Outcome outcome = transactions.execute(status -> apply(event, subscription, userId.get()));
    if (outcome == Outcome.USER_MISSING && !subscription.terminal()) {
      cancelOrphan(event, subscription);
    }
    return outcome;
  }

  /**
   * Cancels at Stripe a live subscription of this API whose user no longer exists (a checkout paid
   * after the account was deleted, or a deletion that committed while this delivery was reading
   * Stripe), outside any transaction, and only then records the event. A failed cancellation is
   * counted in {@code vitadelta.billing.cancel_on_delete.failed} and answered {@code 503} without
   * recording the event, so Stripe's retry of the delivery retries the cancellation.
   */
  private void cancelOrphan(Event event, StripeSubscription subscription) {
    try {
      stripe.cancelSubscription(subscription.id());
      meters.counter("vitadelta.billing.cancel_on_delete.canceled").increment();
      LOG.warn(
          "Stripe event {}: subscription {} belongs to a deleted account and was canceled",
          event.id(),
          subscription.id());
    } catch (StripeApiException e) {
      meters.counter("vitadelta.billing.cancel_on_delete.failed").increment();
      LOG.warn(
          "Stripe event {}: subscription {} of a deleted account could not be canceled: {}",
          event.id(),
          subscription.id(),
          e.getMessage());
      throw ProviderCheckoutService.unavailable();
    }
    accounts.recordEvent(event.id(), event.type(), Outcome.USER_MISSING.tag(), clock.instant());
  }

  /** The subscription an event refers to (and, for a checkout, the session's user). */
  static Optional<Reference> reference(Event event) {
    JsonNode object = event.object();
    String subscriptionId;
    UUID clientReference = null;
    switch (event.type()) {
      case CHECKOUT_COMPLETED -> {
        if (!"subscription".equals(text(object, "mode"))) {
          return Optional.empty();
        }
        subscriptionId = id(object.get("subscription"));
        clientReference = uuid(text(object, "client_reference_id")).orElse(null);
        if (clientReference == null) {
          return Optional.empty();
        }
      }
      case "invoice.paid", PAYMENT_FAILED -> {
        JsonNode parent = object.get("parent");
        JsonNode details = parent == null ? null : parent.get("subscription_details");
        subscriptionId = details == null ? null : id(details.get("subscription"));
        if (subscriptionId == null) {
          // Event payloads of endpoints pinned to an API version before 2025-03-31.
          subscriptionId = id(object.get("subscription"));
        }
      }
      default -> subscriptionId = text(object, "id");
    }
    if (subscriptionId == null || !StripeApi.SUBSCRIPTION_ID.matcher(subscriptionId).matches()) {
      return Optional.empty();
    }
    return Optional.of(new Reference(subscriptionId, clientReference));
  }

  /** The user the subscription was created for by this API, when it is one of ours. */
  private Optional<UUID> owner(Event event, Reference reference, StripeSubscription subscription) {
    Optional<UUID> userId = uuid(subscription.userId());
    if (userId.isEmpty()) {
      LOG.info("Stripe event {}: subscription without the user metadata, ignored", event.id());
      return Optional.empty();
    }
    if (reference.clientReference() != null && !reference.clientReference().equals(userId.get())) {
      LOG.warn("Stripe event {}: session and subscription name different users", event.id());
      return Optional.empty();
    }
    if (!subscription.priceIds().contains(properties.stripe().priceId())) {
      LOG.info("Stripe event {}: subscription for another price, ignored", event.id());
      return Optional.empty();
    }
    return userId;
  }

  private Outcome apply(Event event, StripeSubscription subscription, UUID userId) {
    Instant now = clock.instant();
    if (users.findByIdForUpdate(userId).isEmpty()) {
      // A live subscription is canceled at Stripe after this transaction (cancelOrphan), and the
      // event is recorded only once that succeeded.
      return subscription.terminal()
          ? record(event, Outcome.USER_MISSING, now)
          : Outcome.USER_MISSING;
    }
    if (accounts.eventRecorded(event.id())) {
      return Outcome.DUPLICATE;
    }
    boolean conflict =
        accounts.userOfCustomer(subscription.customer()).filter(u -> !u.equals(userId)).isPresent()
            || accounts
                .userOfSubscription(subscription.id())
                .filter(u -> !u.equals(userId))
                .isPresent();
    if (conflict) {
      LOG.warn("Stripe event {}: customer or subscription belongs to another user", event.id());
      return record(event, Outcome.CUSTOMER_CONFLICT, now);
    }
    Instant endedAt =
        subscription.terminal()
            ? (subscription.endedAt() != null ? subscription.endedAt() : now)
            : null;
    accounts.mergeSubscription(
        subscription.id(),
        userId,
        subscription.customer(),
        subscription.status(),
        subscription.currentPeriodEnd(),
        subscription.currentPeriodPaid() ? subscription.currentPeriodEnd() : null,
        endedAt,
        now);
    boolean changed = reconcile(userId, now);
    mapCustomer(userId, subscription);
    reportSurplus(userId);
    return record(event, changed ? Outcome.APPLIED : Outcome.UNCHANGED, now);
  }

  /**
   * Reports a user who pays for more than one live provider subscription (for example two checkout
   * sessions paid before the first webhook). Nothing is canceled automatically: canceling a paying
   * subscription is worse than a temporary double charge. PRO stays on the subscription paid
   * furthest ahead; the overlap is counted in {@code vitadelta.billing.surplus.detected} and one
   * log line tells the operator which subscriptions to compare, cancel and refund.
   */
  private void reportSurplus(UUID userId) {
    List<String> live = accounts.liveSubscriptions(userId);
    if (live.size() < 2) {
      return;
    }
    meters.counter("vitadelta.billing.surplus.detected").increment();
    String entitled =
        activeSubscriptions
            .findActive(userId)
            .filter(row -> row.source() == SubscriptionSource.PROVIDER)
            .map(Subscription::externalRef)
            .orElse("none");
    LOG.warn(
        "User {} has {} live Stripe subscriptions {} (PRO follows {}); cancel and refund the"
            + " surplus one in the Stripe dashboard (runbook)",
        userId,
        live.size(),
        live,
        entitled);
  }

  /**
   * Makes the user's ACTIVE subscription follow the stored provider subscriptions, under the user's
   * row lock. The entitlement belongs to the live subscription paid furthest ahead ({@link
   * BillingAccountRepository#entitlingSubscription}); an event of one subscription only ever
   * extends or ends that subscription's own row:
   *
   * <ul>
   *   <li>the ACTIVE row belongs to the entitling subscription: its period moves forward;
   *   <li>the ACTIVE row belongs to another provider subscription: the entitling one replaces it
   *       when that one has ended or is paid further ahead;
   *   <li>no subscription is paid ahead and the ACTIVE row's subscription has ended: the user is
   *       downgraded to FREE;
   *   <li>the ACTIVE row is FREE, or PRO from a promo code or a manual grant: the entitling
   *       subscription activates PRO.
   * </ul>
   *
   * @return whether the ACTIVE subscription changed
   */
  private boolean reconcile(UUID userId, Instant now) {
    Optional<SubscriptionState> entitling = accounts.entitlingSubscription(userId, now);
    Optional<Subscription> active = activeSubscriptions.findActive(userId);
    String activeRef =
        active
            .filter(row -> row.planCode() == PlanCode.PRO)
            .filter(row -> row.source() == SubscriptionSource.PROVIDER)
            .map(Subscription::externalRef)
            .orElse(null);
    if (activeRef == null) {
      return entitling.isPresent() && grant(userId, entitling.get());
    }
    boolean activeEnded =
        accounts.subscription(activeRef).map(SubscriptionState::ended).orElse(false);
    if (entitling.isPresent()) {
      SubscriptionState best = entitling.get();
      if (best.externalRef().equals(activeRef)
          || activeEnded
          || best.paidThrough().isAfter(active.get().currentPeriodEnd())) {
        return grant(userId, best);
      }
      return false;
    }
    return activeEnded && subscriptions.endProviderSubscription(userId, activeRef).isPresent();
  }

  private boolean grant(UUID userId, SubscriptionState subscription) {
    ProviderApplication application =
        provider.apply(
            new ActivationRequest.ProviderPeriod(
                userId, subscription.externalRef(), subscription.paidThrough()));
    return application.change() != ProviderChange.UNCHANGED;
  }

  /**
   * Points the billing portal at the customer of the subscription that holds the user's
   * entitlement. Without a provider entitlement, a live subscription sets the customer only when
   * the user has none yet; an ended subscription never sets or replaces it.
   */
  private void mapCustomer(UUID userId, StripeSubscription event) {
    Instant now = clock.instant();
    Optional<String> entitled =
        activeSubscriptions
            .findActive(userId)
            .filter(row -> row.source() == SubscriptionSource.PROVIDER)
            .map(Subscription::externalRef)
            .flatMap(accounts::subscription)
            .map(SubscriptionState::customerRef);
    if (entitled.isPresent()) {
      accounts.saveCustomer(userId, entitled.get(), now);
    } else if (!event.terminal() && accounts.customerOf(userId).isEmpty()) {
      accounts.saveCustomer(userId, event.customer(), now);
    }
  }

  private Outcome record(Event event, Outcome outcome, Instant now) {
    if (!accounts.recordEvent(event.id(), event.type(), outcome.tag(), now)) {
      // Another delivery of the same event committed first; roll this one back.
      throw new IllegalStateException("Stripe event " + event.id() + " was applied concurrently");
    }
    return outcome;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  /** An object id given as a string or as an expanded object. */
  private static String id(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (value.isString()) {
      return value.stringValue();
    }
    return value.isObject() ? text(value, "id") : null;
  }

  private static Optional<UUID> uuid(String value) {
    if (value == null || value.length() != 36) {
      return Optional.empty();
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value.toLowerCase(Locale.ROOT))
          ? Optional.of(parsed)
          : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
