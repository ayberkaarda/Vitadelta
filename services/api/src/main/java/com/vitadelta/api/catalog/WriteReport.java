package com.vitadelta.api.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Row counts of one catalog write, per table. */
public final class WriteReport {

  /** Counts of one table. */
  public static final class TableCounts {
    private int inserted;
    private int updated;
    private int unchanged;
    private int deleted;

    public int inserted() {
      return inserted;
    }

    public int updated() {
      return updated;
    }

    public int unchanged() {
      return unchanged;
    }

    public int deleted() {
      return deleted;
    }

    /** Records one upserted row. */
    void upserted(boolean isNew, boolean changed) {
      if (isNew) {
        inserted++;
      } else if (changed) {
        updated++;
      } else {
        unchanged++;
      }
    }

    void deleted(int count) {
      deleted += count;
    }

    @Override
    public String toString() {
      return "inserted="
          + inserted
          + " updated="
          + updated
          + " unchanged="
          + unchanged
          + " deleted="
          + deleted;
    }
  }

  private final Map<String, TableCounts> tables = new LinkedHashMap<>();

  /** Counts of a table, created on first use. */
  public TableCounts table(String name) {
    return tables.computeIfAbsent(name, n -> new TableCounts());
  }

  public Map<String, TableCounts> tables() {
    return Collections.unmodifiableMap(tables);
  }

  /** Total of inserted, updated and deleted rows over all tables. */
  public int changedRows() {
    return tables.values().stream().mapToInt(t -> t.inserted + t.updated + t.deleted).sum();
  }
}
