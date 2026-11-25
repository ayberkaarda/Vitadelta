package com.vitadelta.api.billing;

import static com.vitadelta.api.entitlement.SqlTime.instant;
import static com.vitadelta.api.entitlement.SqlTime.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access of {@code billing_customer}, {@code billing_subscription} and {@code
 * billing_webhook_event} (migration V7, ADR-0014). Writes for a user run inside the transaction
 * that holds that user's row lock.
 */
@Repository
public class BillingAccountRepository {

  /** The provider value of every row written by the Stripe adapter. */
  static final String STRIPE = "STRIPE";

  private final JdbcClient jdbc;

  public BillingAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The stored, forward-only state of a provider subscription.
   *
   * @param currentPeriodEnd the latest period end ever seen (paid or not)
   * @param paidThrough the latest period end reported as paid, {@code null} when none was
   * @param endedAt when the subscription ended; once set it stays set
   */
  public record SubscriptionState(
      String externalRef,
      UUID userId,
      String customerRef,
      String status,
      Instant currentPeriodEnd,
      Instant paidThrough,
      Instant endedAt) {

    public boolean ended() {
      return endedAt != null;
    }
  }

  /**
   * Records an applied webhook event.
   *
   * @return false when the event id was already recorded (a repeated delivery)
   */
  public boolean recordEvent(String eventId, String eventType, String outcome, Instant now) {
    return jdbc.sql(
                "INSERT INTO billing_webhook_event (provider, event_id, event_type, outcome,"
                    + " processed_at) VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (provider, event_id) DO NOTHING")
            .params(STRIPE, eventId, eventType, outcome, ts(now))
            .update()
        == 1;
  }

  /** Whether the event id was already recorded. */
  public boolean eventRecorded(String eventId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM billing_webhook_event WHERE provider = ?"
                + " AND event_id = ?)")
        .params(STRIPE, eventId)
        .query(Boolean.class)
        .single();
  }

  /** The Stripe customer of the user. */
  public Optional<String> customerOf(UUID userId) {
    return jdbc.sql("SELECT customer_ref FROM billing_customer WHERE user_id = ? AND provider = ?")
        .params(userId, STRIPE)
        .query(String.class)
        .optional();
  }

  /** The user a Stripe customer belongs to. */
  public Optional<UUID> userOfCustomer(String customerRef) {
    return jdbc.sql("SELECT user_id FROM billing_customer WHERE provider = ? AND customer_ref = ?")
        .params(STRIPE, customerRef)
        .query(UUID.class)
        .optional();
  }

  /** Sets the user's Stripe customer (the latest one wins). */
  public void saveCustomer(UUID userId, String customerRef, Instant now) {
    jdbc.sql(
            "INSERT INTO billing_customer (user_id, provider, customer_ref, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET"
                + " provider = EXCLUDED.provider, customer_ref = EXCLUDED.customer_ref,"
                + " updated_at = EXCLUDED.updated_at")
        .params(userId, STRIPE, customerRef, ts(now), ts(now))
        .update();
  }

  /** The user a provider subscription was first recorded for. */
  public Optional<UUID> userOfSubscription(String externalRef) {
    return jdbc.sql(
            "SELECT user_id FROM billing_subscription WHERE provider = ? AND external_ref = ?")
        .params(STRIPE, externalRef)
        .query(UUID.class)
        .optional();
  }

  /**
   * Merges a fetched subscription state into the stored one so that it only moves forward: the
   * period end and the paid-through time are the later of both, an end time once stored is kept
   * (and the status with it), and nothing is marked paid after the subscription ended.
   *
   * @param paidThrough the period end Stripe reports as paid, or {@code null} when the current
   *     period is not paid
   * @return the merged state
   */
  public SubscriptionState mergeSubscription(
      String externalRef,
      UUID userId,
      String customerRef,
      String status,
      Instant currentPeriodEnd,
      Instant paidThrough,
      Instant endedAt,
      Instant now) {
    return jdbc.sql(
            "INSERT INTO billing_subscription (provider, external_ref, user_id, customer_ref,"
                + " status, current_period_end, paid_through, ended_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (provider, external_ref) DO UPDATE SET"
                + " customer_ref = EXCLUDED.customer_ref,"
                + " status = CASE WHEN billing_subscription.ended_at IS NOT NULL"
                + " THEN billing_subscription.status ELSE EXCLUDED.status END,"
                + " current_period_end = GREATEST(billing_subscription.current_period_end,"
                + " EXCLUDED.current_period_end),"
                + " paid_through = CASE WHEN billing_subscription.ended_at IS NOT NULL"
                + " THEN billing_subscription.paid_through"
                + " ELSE GREATEST(billing_subscription.paid_through, EXCLUDED.paid_through) END,"
                + " ended_at = COALESCE(billing_subscription.ended_at, EXCLUDED.ended_at),"
                + " updated_at = EXCLUDED.updated_at"
                + " RETURNING "
                + STATE_COLUMNS)
        .params(
            STRIPE,
            externalRef,
            userId,
            customerRef,
            status,
            ts(currentPeriodEnd),
            ts(paidThrough),
            ts(endedAt),
            ts(now))
        .query(BillingAccountRepository::state)
        .single();
  }

  /** The stored state of a provider subscription. */
  public Optional<SubscriptionState> subscription(String externalRef) {
    return jdbc.sql(
            "SELECT "
                + STATE_COLUMNS
                + " FROM billing_subscription"
                + " WHERE provider = ? AND external_ref = ?")
        .params(STRIPE, externalRef)
        .query(BillingAccountRepository::state)
        .optional();
  }

  /**
   * The live subscription of the user that is paid furthest beyond {@code now} (ties broken by the
   * reference); the PRO entitlement follows it.
   */
  public Optional<SubscriptionState> entitlingSubscription(UUID userId, Instant now) {
    return jdbc.sql(
            "SELECT "
                + STATE_COLUMNS
                + " FROM billing_subscription"
                + " WHERE user_id = ? AND provider = ? AND ended_at IS NULL AND paid_through > ?"
                + " ORDER BY paid_through DESC, external_ref LIMIT 1")
        .params(userId, STRIPE, ts(now))
        .query(BillingAccountRepository::state)
        .optional();
  }

  /** Provider subscriptions of the user that have not ended. */
  public List<String> liveSubscriptions(UUID userId) {
    return jdbc.sql(
            "SELECT external_ref FROM billing_subscription WHERE user_id = ? AND provider = ?"
                + " AND ended_at IS NULL ORDER BY external_ref")
        .params(userId, STRIPE)
        .query(String.class)
        .list();
  }

  /**
   * A claim of the user's checkout creation, with the user's latest session (all three {@code null}
   * when there is none).
   */
  public record CheckoutClaim(
      String previousSessionRef, String previousUrl, Instant previousExpiresAt) {

    /** The latest session when it has not expired at {@code now}. */
    public Optional<String> openSession(Instant now) {
      return previousSessionRef != null && previousExpiresAt.isAfter(now)
          ? Optional.of(previousSessionRef)
          : Optional.empty();
    }
  }

  /**
   * Claims the creation of a checkout for the user until {@code leaseUntil}, unless another request
   * holds an unexpired claim.
   *
   * @return the claim with the user's latest session, or empty while another creation runs
   */
  public Optional<CheckoutClaim> claimCheckout(UUID userId, Instant leaseUntil, Instant now) {
    return jdbc.sql(
            "INSERT INTO billing_checkout_session (user_id, provider, claim_until, created_at)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (user_id) DO UPDATE SET claim_until = EXCLUDED.claim_until"
                + " WHERE billing_checkout_session.claim_until IS NULL"
                + " OR billing_checkout_session.claim_until <= EXCLUDED.created_at"
                + " RETURNING session_ref, url, expires_at")
        .params(userId, STRIPE, ts(leaseUntil), ts(now))
        .query((rs, row) ->
            new CheckoutClaim(
                rs.getString("session_ref"), rs.getString("url"), instant(rs, "expires_at")))
        .optional();
  }

  /** Records the session the claim created and releases the claim. */
  public void openCheckout(UUID userId, String sessionRef, String url, Instant expiresAt) {
    jdbc.sql(
            "UPDATE billing_checkout_session SET session_ref = ?, url = ?, expires_at = ?,"
                + " claim_until = NULL WHERE user_id = ?")
        .params(sessionRef, url, ts(expiresAt), userId)
        .update();
  }

  /** Releases the claim and keeps the latest session as it was. */
  public void releaseCheckout(UUID userId) {
    jdbc.sql("UPDATE billing_checkout_session SET claim_until = NULL WHERE user_id = ?")
        .param(userId)
        .update();
  }

  /** Forgets the user's latest session (its checkout completed). */
  public void clearCheckout(UUID userId) {
    jdbc.sql(
            "UPDATE billing_checkout_session SET session_ref = NULL, url = NULL, expires_at = NULL"
                + " WHERE user_id = ?")
        .param(userId)
        .update();
  }

  private static final String STATE_COLUMNS =
      "external_ref, user_id, customer_ref, status, current_period_end, paid_through, ended_at";

  private static SubscriptionState state(ResultSet rs, int row) throws SQLException {
    return new SubscriptionState(
        rs.getString("external_ref"),
        rs.getObject("user_id", UUID.class),
        rs.getString("customer_ref"),
        rs.getString("status"),
        instant(rs, "current_period_end"),
        instant(rs, "paid_through"),
        instant(rs, "ended_at"));
  }
}
