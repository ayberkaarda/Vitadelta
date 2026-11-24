package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Price of one product at one retailer in one country. */
@Entity
@Table(name = "offer")
public class Offer {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id")
  private UUID id;

  @Column(name = "product_id", nullable = false)
  private UUID productId;

  @Column(name = "country_code", nullable = false, length = 2)
  private String countryCode;

  @Column(name = "retailer", nullable = false, length = 100)
  private String retailer;

  @Column(name = "price", nullable = false, precision = 12, scale = 2)
  private BigDecimal price;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "in_stock", nullable = false)
  private boolean inStock;

  @Column(name = "url", nullable = false, length = 2048)
  private String url;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;

  protected Offer() {}

  public Offer(UUID productId, OfferValues values) {
    this.productId = productId;
    this.countryCode = values.countryCode();
    this.retailer = values.retailer();
    assign(values);
  }

  /**
   * Copies price, currency, stock, url and check time; returns whether anything changed.
   *
   * @throws IllegalArgumentException when the natural key differs
   */
  public boolean update(OfferValues values) {
    if (!countryCode.equals(values.countryCode()) || !retailer.equals(values.retailer())) {
      throw new IllegalArgumentException("offer natural key cannot change: " + id);
    }
    boolean changed =
        Changes.differ(price, values.price())
            || Changes.differ(currency, values.currency())
            || inStock != values.inStock()
            || Changes.differ(url, values.url())
            || Changes.differ(checkedAt, values.checkedAt());
    if (changed) {
      assign(values);
    }
    return changed;
  }

  private void assign(OfferValues values) {
    this.price = values.price();
    this.currency = values.currency();
    this.inStock = values.inStock();
    this.url = values.url();
    this.checkedAt = values.checkedAt();
  }

  public UUID getId() {
    return id;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public String getRetailer() {
    return retailer;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public String getCurrency() {
    return currency;
  }

  public boolean isInStock() {
    return inStock;
  }

  public String getUrl() {
    return url;
  }

  public Instant getCheckedAt() {
    return checkedAt;
  }
}
