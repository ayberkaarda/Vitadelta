package com.vitadelta.api.alerts;

import com.vitadelta.api.imports.OfferChangeListener;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one {@code OFFERS_CHANGED} outbox event per changed (product, country) pair inside the
 * write transaction of an import or seed run (section 8.6, ADR-0008), so the events commit or roll
 * back together with the offers. The imports package calls it only with pairs whose effective state
 * ({@code INCLUDE_OUT_OF_STOCK} basis) differs before and after, so an unchanged re-import or
 * re-seed writes nothing.
 *
 * <p>{@code catalogVersion} is the version the write transaction commits with, passed by the
 * imports seam: the listener runs right after the single version bump of that transaction.
 */
@Component
public class OffersChangedEmitter implements OfferChangeListener {

  private static final Logger LOG = LoggerFactory.getLogger(OffersChangedEmitter.class);

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final OutboxRepository outbox;
  private final Clock clock;

  public OffersChangedEmitter(OutboxRepository outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void offersChanged(List<OfferPairChange> changes, long catalogVersion) {
    if (changes.isEmpty()) {
      return;
    }
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
