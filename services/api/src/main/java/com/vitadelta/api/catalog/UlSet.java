package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tolerable upper intake level set (EFSA, IOM). */
@Entity
@Table(name = "ul_set")
public class UlSet {

  @Id
  @Column(name = "code", length = 40)
  private String code;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "source_citation", nullable = false, length = 1000)
  private String sourceCitation;

  protected UlSet() {}

  public UlSet(String code, String name, String sourceCitation) {
    this.code = code;
    this.name = name;
    this.sourceCitation = sourceCitation;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(String name, String sourceCitation) {
    boolean changed =
        Changes.differ(this.name, name) || Changes.differ(this.sourceCitation, sourceCitation);
    this.name = name;
    this.sourceCitation = sourceCitation;
    return changed;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getSourceCitation() {
    return sourceCitation;
  }
}
