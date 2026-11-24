package com.vitadelta.api.common.paging;

import java.util.List;

/**
 * A page of items with its metadata ({@code { items, meta }}).
 *
 * @param <T> item type
 */
public record Page<T>(List<T> items, PageMeta meta) {

  public Page {
    items = List.copyOf(items);
  }

  /** The page of {@code all} selected by {@code query} (for lists that are already in memory). */
  public static <T> Page<T> slice(List<T> all, PageQuery query) {
    int from = (int) Math.min(query.offset(), all.size());
    int to = (int) Math.min((long) from + query.pageSize(), all.size());
    return new Page<>(all.subList(from, to), query.meta(all.size()));
  }
}
