package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link UlSet}. Writes go through {@link CatalogWriter} inside {@link
 * CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface UlSetRepository extends Repository<UlSet, String> {

  List<UlSet> findAll();

  Optional<UlSet> findById(String id);
}
