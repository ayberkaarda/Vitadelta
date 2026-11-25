package com.vitadelta.api.alerts;

import com.vitadelta.api.imports.OfferChangeListener;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one {@code OFFERS_CHANGED} outbox event per changed (product, country) pair inside the
 * import's write transaction (section 8.6, ADR-0008), so the events commit or roll back together
 * with the offers. The imports package calls it only with pairs whose effective state ({@code
 * INCLUDE_OUT_OF_STOCK} basis) differs before and after, so an unchanged re-import writes nothing.
 *
 * <p>{@code catalogVersion} is the version the import commits with: the listener runs before the
 * single bump of the import transaction (a non-empty change list always changes rows), so it reads
 * the current version under the row lock of {@code catalog_version} and adds one. The lock is the
 * one the bump takes a moment later in the same transaction.
 */
@Component
public class OffersChangedEmitter implements OfferChangeListener {

  private static final Logger LOG = LoggerFactory.getLogger(OffersChangedEmitter.class);

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;
  private final OutboxRepository outbox;
  private final Clock clock;

  public OffersChangedEmitter(JdbcClient jdbc, OutboxRepository outbox, Clock clock) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void offersChanged(List<OfferPairChange> changes) {
    if (changes.isEmpty()) {
      return;
    }
    long catalogVersion =
        jdbc.sql("SELECT version + 1 FROM catalog_version WHERE id = 1 FOR UPDATE")
            .query(Long.class)
            .single();
    Instant now = clock.instant();
    int written = 0;
    for (OfferPairChange change : changes) {
      if (change.productId() == null) {
        continue;
      }
      NotificationPayloads.OffersChanged payload =
          new NotificationPayloads.OffersChanged(
              change.productId(),
              change.countryCode(),
              OfferState.of(change.before()),
              OfferState.of(change.after()),
              catalogVersion);
      outbox.insert(
          OutboxEventType.OFFERS_CHANGED,
          OutboxRepository.PRODUCT_COUNTRY,
          change.productId() + ":" + change.countryCode(),
          JSON.writeValueAsString(payload),
          now);
      written++;
    }
    LOG.info("OFFERS_CHANGED events written: {} (catalogVersion={})", written, catalogVersion);
  }
}
