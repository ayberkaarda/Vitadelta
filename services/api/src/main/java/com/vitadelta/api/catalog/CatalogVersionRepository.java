package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link CatalogVersion}. Writes go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface CatalogVersionRepository extends Repository<CatalogVersion, Short> {

  List<CatalogVersion> findAll();

  Optional<CatalogVersion> findById(Short id);
}
