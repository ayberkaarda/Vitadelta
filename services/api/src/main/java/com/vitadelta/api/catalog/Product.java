package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One sellable SKU with a fixed formulation. {@link #getCode()} is the business key; the uuid id is
 * internal and stable once created.
 */
@Entity
@Table(name = "product")
public class Product {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id")
  private UUID id;

  @Column(name = "code", nullable = false, unique = true, length = 40)
  private String code;

  @Column(name = "brand", nullable = false, length = 200)
  private String brand;

  @Column(name = "name", nullable = false, length = 300)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "dosage_form", nullable = false, length = 20)
  private DosageForm dosageForm;

  @Column(name = "units_per_container", nullable = false)
  private int unitsPerContainer;

  @Column(name = "units_per_serving", nullable = false)
  private int unitsPerServing;

  @Column(name = "servings_per_day", nullable = false, precision = 4, scale = 2)
  private BigDecimal servingsPerDay;

  @Column(name = "gtin", length = 14)
  private String gtin;

  @Column(name = "source", length = 500)
  private String source;

  @Column(name = "source_ref", length = 100)
  private String sourceRef;

  @Column(name = "source_entry_date")
  private LocalDate sourceEntryDate;

  @Column(name = "synthetic", nullable = false)
  private boolean synthetic;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Product() {}

  public Product(ProductDetails details, Instant now) {
    this.code = details.code();
    assign(details);
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Copies the given attributes; when anything changed, {@code updated_at} is set to {@code now}.
   *
   * @return whether anything changed
   * @throws IllegalArgumentException when the code differs (the code is the business key)
   */
  public boolean update(ProductDetails details, Instant now) {
    if (!code.equals(details.code())) {
      throw new IllegalArgumentException("product code cannot change: " + code);
    }
    if (sameValues(details)) {
      return false;
    }
    assign(details);
    this.updatedAt = now;
    return true;
  }

  private boolean sameValues(ProductDetails d) {
    return !(Changes.differ(brand, d.brand())
        || Changes.differ(name, d.name())
        || Changes.differ(dosageForm, d.dosageForm())
        || unitsPerContainer != d.unitsPerContainer()
        || unitsPerServing != d.unitsPerServing()
        || Changes.differ(servingsPerDay, d.servingsPerDay())
        || Changes.differ(gtin, d.gtin())
        || Changes.differ(source, d.source())
        || Changes.differ(sourceRef, d.sourceRef())
        || Changes.differ(sourceEntryDate, d.sourceEntryDate())
        || synthetic != d.synthetic());
  }

  private void assign(ProductDetails d) {
    this.brand = d.brand();
    this.name = d.name();
    this.dosageForm = d.dosageForm();
    this.unitsPerContainer = d.unitsPerContainer();
    this.unitsPerServing = d.unitsPerServing();
    this.servingsPerDay = d.servingsPerDay();
    this.gtin = d.gtin();
    this.source = d.source();
    this.sourceRef = d.sourceRef();
    this.sourceEntryDate = d.sourceEntryDate();
    this.synthetic = d.synthetic();
  }

  /** The business attributes of this product. */
  public ProductDetails toDetails() {
    return new ProductDetails(
        code,
        brand,
        name,
        dosageForm,
        unitsPerContainer,
        unitsPerServing,
        servingsPerDay,
        gtin,
        source,
        sourceRef,
        sourceEntryDate,
        synthetic);
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getBrand() {
    return brand;
  }

  public String getName() {
    return name;
  }

  public DosageForm getDosageForm() {
    return dosageForm;
  }

  public int getUnitsPerContainer() {
    return unitsPerContainer;
  }

  public int getUnitsPerServing() {
    return unitsPerServing;
  }

  public BigDecimal getServingsPerDay() {
    return servingsPerDay;
  }

  public String getGtin() {
    return gtin;
  }

  public String getSource() {
    return source;
  }

  public String getSourceRef() {
    return sourceRef;
  }

  public LocalDate getSourceEntryDate() {
    return sourceEntryDate;
  }

  public boolean isSynthetic() {
    return synthetic;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
