package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link UnitConversion}. Writes go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface UnitConversionRepository extends Repository<UnitConversion, UnitConversionId> {

  List<UnitConversion> findAll();

  Optional<UnitConversion> findById(UnitConversionId id);
}
