package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.config.properties.OperationsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/** Identity purges of the housekeeping job (section 8.11). */
class IdentityHousekeepingIntegrationTest extends IdentityIntegrationTest {

  @Autowired IdentityHousekeepingJob job;
  @Autowired IdentityProperties properties;
  @Autowired OperationsProperties operations;
  @Autowired ApplicationEventPublisher events;
  @Autowired JobLock jobLock;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DataSource dataSource;

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private UUID user(String email, Instant createdAt, boolean verified, Instant lastLogin) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO app_user (id, email, password_hash, email_verified_at, last_login_at,"
                + " created_at, updated_at) VALUES (?, ?, 'x', ?, ?, ?, ?)")
        .params(
            id,
            email,
            verified ? at(createdAt) : null,
            lastLogin == null ? null : at(lastLogin),
            at(createdAt),
            at(createdAt))
        .update();
    return id;
  }

  private long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  @Test
  void purgesExpiredIdentityRowsInBatches() {
    Instant now = clock.instant();
    Instant ancient = now.minus(Duration.ofDays(400));
    String staleAnon = SecureTokens.sha256Hex(UUID.randomUUID().toString());
    String freshAnon = SecureTokens.sha256Hex(UUID.randomUUID().toString());
    jdbc.sql(
            "INSERT INTO anonymous_identity (id_hash, first_seen_at, last_seen_at) VALUES"
                + " (?, ?, ?), (?, ?, ?)")
        .params(
            staleAnon,
            at(ancient),
            at(now.minus(Duration.ofDays(181))),
            freshAnon,
            at(ancient),
            at(now.minus(Duration.ofDays(179))))
        .update();

    UUID keeper = user(uniqueEmail(), ancient, true, null);
    String[] tokens = new String[6];
    for (int i = 0; i < tokens.length; i++) {
      tokens[i] = SecureTokens.sha256Hex(UUID.randomUUID().toString());
    }
    jdbc.sql(
            "INSERT INTO email_token (id, user_id, kind, token_hash, expires_at, used_at,"
                + " created_at) VALUES"
                + " (?, ?, 'VERIFY_EMAIL', ?, ?, NULL, ?),"
                + " (?, ?, 'RESET_PASSWORD', ?, ?, ?, ?),"
                + " (?, ?, 'VERIFY_EMAIL', ?, ?, NULL, ?)")
        .params(
            UUID.randomUUID(),
            keeper,
            tokens[0],
            at(now.minus(Duration.ofDays(8))),
            at(ancient),
            UUID.randomUUID(),
            keeper,
            tokens[1],
            at(now.plus(Duration.ofHours(1))),
            at(now.minus(Duration.ofDays(8))),
            at(ancient),
            UUID.randomUUID(),
            keeper,
            tokens[2],
            at(now.minus(Duration.ofDays(6))),
            at(ancient))
        .update();

    UUID family = UUID.randomUUID();
    UUID expired = UUID.randomUUID();
    UUID revokedLongAgo = UUID.randomUUID();
    UUID graceRecent = UUID.randomUUID();
    UUID live = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO refresh_token (id, user_id, token_hash, family_id, expires_at,"
                + " revoked_at, replaced_by, grace_successor_enc, created_at) VALUES"
                + " (?, ?, ?, ?, ?, NULL, NULL, NULL, ?),"
                + " (?, ?, ?, ?, ?, ?, NULL, NULL, ?),"
                + " (?, ?, ?, ?, ?, NULL, NULL, NULL, ?),"
                + " (?, ?, ?, ?, ?, ?, ?, 'ciphertext', ?)")
        .params(
            expired,
            keeper,
            tokens[3],
            family,
            at(now.minus(Duration.ofDays(31))),
            at(ancient),
            revokedLongAgo,
            keeper,
            tokens[4],
            family,
            at(now.plus(Duration.ofDays(1))),
            at(now.minus(Duration.ofDays(31))),
            at(ancient),
            live,
            keeper,
            tokens[5],
            family,
            at(now.plus(Duration.ofDays(10))),
            at(ancient),
            graceRecent,
            keeper,
            SecureTokens.sha256Hex("grace" + UUID.randomUUID()),
            family,
            at(now.plus(Duration.ofDays(10))),
            at(now.minus(Duration.ofSeconds(30))),
            live,
            at(ancient))
        .update();

    UUID unverifiedOld = user(uniqueEmail(), now.minus(Duration.ofDays(31)), false, null);
    UUID unverifiedLoggedIn = user(uniqueEmail(), now.minus(Duration.ofDays(31)), false, now);
    UUID unverifiedRecent = user(uniqueEmail(), now.minus(Duration.ofDays(29)), false, null);
    jdbc.sql(
            "INSERT INTO anonymous_identity (id_hash, first_seen_at, last_seen_at,"
                + " converted_user_id) VALUES (?, ?, ?, ?)")
        .params(
            SecureTokens.sha256Hex(UUID.randomUUID().toString()), at(now), at(now), unverifiedOld)
        .update();

    IdentityHousekeepingJob batched =
        new IdentityHousekeepingJob(
            jdbc,
            new IdentityProperties(
                properties.weakPasswordsFile(),
                properties.anonymousRetention(),
                properties.emailTokenRetention(),
                properties.refreshTokenRetention(),
                1),
            operations,
            events,
            jobLock,
            new SimpleMeterRegistry(),
            clock,
            transactionManager);
    Map<String, Integer> counts = batched.run();

    assertThat(counts.get("anonymous_identity")).isGreaterThanOrEqualTo(1);
    assertThat(count("SELECT count(*) FROM anonymous_identity WHERE id_hash = ?", staleAnon))
        .isZero();
    assertThat(count("SELECT count(*) FROM anonymous_identity WHERE id_hash = ?", freshAnon))
        .isOne();
    assertThat(counts.get("email_token")).isGreaterThanOrEqualTo(2);
    assertThat(count("SELECT count(*) FROM email_token WHERE user_id = ?", keeper)).isOne();
    assertThat(counts.get("refresh_token")).isGreaterThanOrEqualTo(2);
    assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", keeper)).isEqualTo(2);
    assertThat(counts.get("refresh_token_grace")).isGreaterThanOrEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM refresh_token WHERE id = ? AND grace_successor_enc IS NULL",
                graceRecent))
        .isOne();
    assertThat(counts.get("unverified_user")).isGreaterThanOrEqualTo(1);
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", unverifiedOld)).isZero();
    assertThat(deletionEvents.deleted).contains(unverifiedOld);
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", unverifiedLoggedIn)).isOne();
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", unverifiedRecent)).isOne();
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", keeper)).isOne();
    assertThat(
            count(
                "SELECT count(*) FROM anonymous_identity WHERE converted_user_id IS NULL"
                    + " AND last_seen_at = ?",
                at(now)))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void theAdvisoryLockPreventsConcurrentRunsAndRunsAreMetered() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
      lock.setLong(1, JobLockKeys.HOUSEKEEPING_IDENTITY);
      lock.executeQuery().close();
      try {
        assertThat(job.run()).isEmpty();
      } finally {
        try (PreparedStatement unlock =
            connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1, JobLockKeys.HOUSEKEEPING_IDENTITY);
          unlock.executeQuery().close();
        }
      }
    }
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    IdentityHousekeepingJob metered =
        new IdentityHousekeepingJob(
            jdbc, properties, operations, events, jobLock, meters, clock, transactionManager);
    assertThat(metered.run())
        .containsOnlyKeys(
            "anonymous_identity",
            "email_token",
            "refresh_token",
            "refresh_token_grace",
            "unverified_user");
    assertThat(meters.find(IdentityHousekeepingJob.METRIC).counters()).hasSize(5);
    job.scheduled();
  }
}
