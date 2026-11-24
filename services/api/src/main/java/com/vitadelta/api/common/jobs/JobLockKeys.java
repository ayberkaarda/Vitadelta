package com.vitadelta.api.common.jobs;

/**
 * Fixed PostgreSQL advisory-lock keys of the scheduled jobs and the admin bootstrap (ADR-0011). One
 * key per job; keys are never reused. The high 32 bits spell {@code VDJB} so the keys do not
 * collide with advisory locks of other applications sharing the database.
 */
public final class JobLockKeys {

  private static final long NAMESPACE = 0x5644_4A42L << 32;

  /** {@code OutboxProcessor} (package {@code alerts}). */
  public static final long OUTBOX_PROCESSOR = NAMESPACE | 1;

  /** {@code FxRefreshJob} (package {@code fx}). */
  public static final long FX_REFRESH = NAMESPACE | 2;

  /** {@code SubscriptionExpiryJob} (packages {@code entitlement} / {@code billing}). */
  public static final long SUBSCRIPTION_EXPIRY = NAMESPACE | 3;

  /** {@code HousekeepingJob} purges of the identity tables (package {@code identity}). */
  public static final long HOUSEKEEPING_IDENTITY = NAMESPACE | 4;

  /** {@code HousekeepingJob} purges of the outbox tables (package {@code alerts}). */
  public static final long HOUSEKEEPING_ALERTS = NAMESPACE | 5;

  /** Admin bootstrap at startup (package {@code identity}). */
  public static final long ADMIN_BOOTSTRAP = NAMESPACE | 6;

  private JobLockKeys() {}
}
