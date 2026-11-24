package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link ReferenceValue}. Writes go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface ReferenceValueRepository extends Repository<ReferenceValue, ReferenceValueId> {

  List<ReferenceValue> findAll();

  Optional<ReferenceValue> findById(ReferenceValueId id);
}
