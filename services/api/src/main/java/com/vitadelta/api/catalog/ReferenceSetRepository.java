package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link ReferenceSet}. Writes go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface ReferenceSetRepository extends Repository<ReferenceSet, String> {

  List<ReferenceSet> findAll();

  Optional<ReferenceSet> findById(String id);
}
