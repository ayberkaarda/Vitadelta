package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Availability of a product in one country. */
@Entity
@Table(name = "product_availability")
public class ProductAvailability {

  @EmbeddedId private ProductAvailabilityId id;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private AvailabilityStatus status;

  @Column(name = "note", length = 500)
  private String note;

  protected ProductAvailability() {}

  public ProductAvailability(ProductAvailabilityId id, AvailabilityStatus status, String note) {
    this.id = id;
    this.status = status;
    this.note = note;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(AvailabilityStatus status, String note) {
    boolean changed = Changes.differ(this.status, status) || Changes.differ(this.note, note);
    if (changed) {
      this.status = status;
      this.note = note;
    }
    return changed;
  }

  public ProductAvailabilityId getId() {
    return id;
  }

  public AvailabilityStatus getStatus() {
    return status;
  }

  public String getNote() {
    return note;
  }
}
