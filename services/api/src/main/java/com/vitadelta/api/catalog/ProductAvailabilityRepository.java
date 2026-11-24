package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link ProductAvailability}. Writes go through {@link CatalogWriter}
 * inside {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface ProductAvailabilityRepository
    extends Repository<ProductAvailability, ProductAvailabilityId> {

  List<ProductAvailability> findAll();

  Optional<ProductAvailability> findById(ProductAvailabilityId id);

  List<ProductAvailability> findByIdProductId(UUID productId);
}
