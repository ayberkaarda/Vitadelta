package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link UpperLimit}. Writes go through {@link CatalogWriter} inside {@link
 * CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface UpperLimitRepository extends Repository<UpperLimit, UpperLimitId> {

  List<UpperLimit> findAll();

  Optional<UpperLimit> findById(UpperLimitId id);
}
