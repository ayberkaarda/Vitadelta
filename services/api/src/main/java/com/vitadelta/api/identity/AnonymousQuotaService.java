package com.vitadelta.api.identity;

import static com.vitadelta.api.identity.UserRepository.ts;

import com.vitadelta.api.config.properties.AnonymousProperties;
import java.time.Clock;
import java.util.OptionalInt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The anonymous trial quota (sections 7.6 and 8.4), for the recommendation module and {@code GET
 * /session/quota}. Reservation and refund are single atomic statements, each in its own short
 * transaction ({@code REQUIRES_NEW}), so no transaction is held across the scoring call:
 *
 * <pre>{@code
 * OptionalInt used = quota.reserve(identity.idHash());   // empty -> 403 quota_exceeded
 * try {
 *   ... call the scoring service ...
 * } catch (ScoringUnavailable e) {
 *   quota.refund(identity.idHash());
 *   throw e;
 * }
 * }</pre>
 */
@Service
public class AnonymousQuotaService {

  private final JdbcClient jdbc;
  private final AnonymousProperties properties;
  private final Clock clock;
  private final TransactionTemplate newTransaction;

  public AnonymousQuotaService(
      JdbcClient jdbc,
      AnonymousProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = clock;
    this.newTransaction = new TransactionTemplate(transactionManager);
    this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Queries an anonymous visitor may run ({@code VITADELTA_ANON_QUERY_LIMIT}). */
  public int queryLimit() {
    return properties.queryLimit();
  }

  /** Result rows shown unlocked ({@code VITADELTA_ANON_VISIBLE_RESULTS}). */
  public int visibleResults() {
    return properties.visibleResults();
  }

  /**
   * Reserves one query: {@code UPDATE anonymous_identity SET queries_used = queries_used + 1,
   * last_seen_at = now WHERE id_hash = ? AND queries_used < limit RETURNING queries_used}.
   *
   * @return the new number of used queries, or empty when the quota is used up (or the identity
   *     does not exist)
   */
  public OptionalInt reserve(String idHash) {
    Integer used =
        newTransaction.execute(status ->
            jdbc.sql(
                    "UPDATE anonymous_identity SET queries_used = queries_used + 1,"
                        + " last_seen_at = ? WHERE id_hash = ? AND queries_used < ?"
                        + " RETURNING queries_used")
                .params(ts(clock.instant()), idHash, properties.queryLimit())
                .query(Integer.class)
                .optional()
                .orElse(null));
    return used == null ? OptionalInt.empty() : OptionalInt.of(used);
  }

  /**
   * Returns a reserved query after a failed scoring call: {@code UPDATE anonymous_identity SET
   * queries_used = queries_used - 1 WHERE id_hash = ? AND queries_used > 0}.
   *
   * @return whether a query was returned
   */
  public boolean refund(String idHash) {
    Integer changed =
        newTransaction.execute(status ->
            jdbc.sql(
                    "UPDATE anonymous_identity SET queries_used = queries_used - 1"
                        + " WHERE id_hash = ? AND queries_used > 0")
                .param(idHash)
                .update());
    return changed != null && changed == 1;
  }

  /** Queries used so far by the identity (0 when the row does not exist). */
  public int queriesUsed(String idHash) {
    return jdbc.sql("SELECT queries_used FROM anonymous_identity WHERE id_hash = ?")
        .param(idHash)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }
}
