package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link Country}. Writes go through {@link CatalogWriter} inside {@link
 * CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface CountryRepository extends Repository<Country, String> {

  List<Country> findAll();

  Optional<Country> findById(String id);
}
