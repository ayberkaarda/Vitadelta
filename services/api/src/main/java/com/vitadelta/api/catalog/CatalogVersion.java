package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * The single catalog version row. It is read-only for the application: the only write is the bump
 * performed by {@link CatalogWriteTransaction}.
 */
@Entity
@Immutable
@Table(name = "catalog_version")
public class CatalogVersion {

  /** Id of the single row. */
  public static final short ROW_ID = 1;

  @Id
  @Column(name = "id")
  private Short id;

  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "bumped_at", nullable = false)
  private Instant bumpedAt;

  protected CatalogVersion() {}

  public Short getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public Instant getBumpedAt() {
    return bumpedAt;
  }
}
