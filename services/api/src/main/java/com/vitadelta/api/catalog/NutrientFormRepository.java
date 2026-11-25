package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link NutrientForm}. Writes go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface NutrientFormRepository extends Repository<NutrientForm, String> {

  List<NutrientForm> findAll();

  Optional<NutrientForm> findById(String id);
}
