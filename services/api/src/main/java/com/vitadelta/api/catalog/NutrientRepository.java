package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link Nutrient}. Writes go through {@link CatalogWriter} inside {@link
 * CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface NutrientRepository extends Repository<Nutrient, String> {

  List<Nutrient> findAll();

  Optional<Nutrient> findById(String id);
}
