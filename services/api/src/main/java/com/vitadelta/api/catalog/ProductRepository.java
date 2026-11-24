package com.vitadelta.api.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Read-only repository of {@link Product}. Writes go through {@link CatalogWriter} inside {@link
 * CatalogWriteTransaction}, so no write can skip the catalog version bump.
 */
public interface ProductRepository extends Repository<Product, UUID> {

  List<Product> findAll();

  Optional<Product> findById(UUID id);

  Optional<Product> findByCode(String code);
}
