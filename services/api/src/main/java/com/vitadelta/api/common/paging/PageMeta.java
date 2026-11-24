package com.vitadelta.api.common.paging;

/**
 * Page metadata of list responses ({@code meta: { page, pageSize, total }}).
 *
 * @param total number of items over all pages
 */
public record PageMeta(int page, int pageSize, long total) {

  public PageMeta {
    if (page < 1 || pageSize < 1 || total < 0) {
      throw new IllegalArgumentException("invalid page metadata");
    }
  }
}
