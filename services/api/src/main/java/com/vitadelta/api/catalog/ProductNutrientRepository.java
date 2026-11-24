package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link ProductNutrient}. Writes go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface ProductNutrientRepository extends Repository<ProductNutrient, ProductNutrientId> {

  List<ProductNutrient> findAll();

  Optional<ProductNutrient> findById(ProductNutrientId id);

  List<ProductNutrient> findByIdProductId(UUID productId);
}
