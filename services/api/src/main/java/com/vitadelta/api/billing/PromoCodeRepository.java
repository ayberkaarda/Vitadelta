package com.vitadelta.api.billing;

import static com.vitadelta.api.entitlement.SqlTime.instant;
import static com.vitadelta.api.entitlement.SqlTime.ts;

import com.vitadelta.api.entitlement.PlanCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access of {@code promo_code} and {@code promo_redemption}. Codes are {@code citext}: every
 * lookup casts the parameter to {@code citext} so it is case-insensitive (a plain text parameter
 * would select the case-sensitive operator).
 */
@Repository
public class PromoCodeRepository {

  private static final String COLUMNS =
      "code, plan_code, duration_days, max_redemptions, redeemed_count, expires_at, created_by";

  private final JdbcClient jdbc;

  public PromoCodeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** What a consumed redemption grants. */
  public record Grant(PlanCode planCode, int durationDays) {}

  /**
   * Inserts the code unless it exists.
   *
   * @return whether the row was inserted
   */
  public boolean insertIfAbsent(PromoCodeRecord code, Instant now) {
    return jdbc.sql(
                "INSERT INTO promo_code (code, plan_code, duration_days, max_redemptions,"
                    + " redeemed_count, expires_at, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?, 0, ?, ?, ?) ON CONFLICT (code) DO NOTHING")
            .params(
                code.code(),
                code.planCode().name(),
                code.durationDays(),
                code.maxRedemptions(),
                ts(code.expiresAt()),
                code.createdBy(),
                ts(now))
            .update()
        == 1;
  }

  /** Every code, newest first. */
  public List<PromoCodeRecord> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM promo_code ORDER BY created_at DESC, code")
        .query(PromoCodeRepository::map)
        .list();
  }

  /** The code, case-insensitively. */
  public Optional<PromoCodeRecord> find(String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM promo_code WHERE code = CAST(? AS citext)")
        .param(code)
        .query(PromoCodeRepository::map)
        .optional();
  }

  /**
   * Deletes the code (case-insensitively) with its redemptions.
   *
   * @return whether it existed
   */
  public boolean delete(String code) {
    return jdbc.sql("DELETE FROM promo_code WHERE code = CAST(? AS citext)").param(code).update()
        == 1;
  }

  /**
   * Records the redemption of an existing code by the user ({@code promo_redemption}, unique per
   * code and user).
   *
   * @return false when the code does not exist or the user already redeemed it
   */
  public boolean insertRedemption(String code, UUID userId, Instant now) {
    return jdbc.sql(
                "INSERT INTO promo_redemption (id, code, user_id, redeemed_at)"
                    + " SELECT ?, code, ?, ? FROM promo_code WHERE code = CAST(? AS citext)"
                    + " ON CONFLICT (code, user_id) DO NOTHING")
            .params(UUID.randomUUID(), userId, ts(now), code)
            .update()
        == 1;
  }

  /**
   * Consumes one redemption with one atomic conditional update: {@code UPDATE promo_code SET
   * redeemed_count = redeemed_count + 1 WHERE code = ? AND redeemed_count < max_redemptions AND
   * (expires_at IS NULL OR expires_at > now) RETURNING ...}.
   *
   * @return the grant, or empty when the code is exhausted, expired or unknown
   */
  public Optional<Grant> consume(String code, Instant now) {
    return jdbc.sql(
            "UPDATE promo_code SET redeemed_count = redeemed_count + 1"
                + " WHERE code = CAST(? AS citext) AND redeemed_count < max_redemptions"
                + " AND (expires_at IS NULL OR expires_at > ?)"
                + " RETURNING plan_code, duration_days")
        .params(code, ts(now))
        .query((rs, row) ->
            new Grant(PlanCode.valueOf(rs.getString("plan_code")), rs.getInt("duration_days")))
        .optional();
  }

  private static PromoCodeRecord map(ResultSet rs, int row) throws SQLException {
    return new PromoCodeRecord(
        rs.getString("code"),
        PlanCode.valueOf(rs.getString("plan_code")),
        rs.getInt("duration_days"),
        rs.getInt("max_redemptions"),
        rs.getInt("redeemed_count"),
        instant(rs, "expires_at"),
        rs.getObject("created_by", UUID.class));
  }
}
