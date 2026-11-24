package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link Offer}. Writes go through {@link CatalogWriter} inside {@link
 * CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface OfferRepository extends Repository<Offer, UUID> {

  List<Offer> findAll();

  Optional<Offer> findById(UUID id);

  List<Offer> findByProductIdAndCountryCode(UUID productId, String countryCode);

  Optional<Offer> findByProductIdAndCountryCodeAndRetailer(
      UUID productId, String countryCode, String retailer);
}
