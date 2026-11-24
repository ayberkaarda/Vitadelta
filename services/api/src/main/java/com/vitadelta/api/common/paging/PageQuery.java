package com.vitadelta.api.common.paging;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import java.util.ArrayList;
import java.util.List;

/**
 * A validated page request of the public API: {@code page >= 1} (default 1), {@code 1 <= pageSize
 * <= 100} (default 20).
 *
 * @param page 1-based page number
 * @param pageSize items per page
 */
public record PageQuery(int page, int pageSize) {

  public static final int DEFAULT_PAGE = 1;
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  public PageQuery {
    if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("invalid page request: " + page + "/" + pageSize);
    }
  }

  /**
   * Validates request parameters; {@code null} means the default.
   *
   * @throws ProblemException {@code validation_failed} with one error per invalid parameter
   */
  public static PageQuery of(Integer page, Integer pageSize) {
    int p = page == null ? DEFAULT_PAGE : page;
    int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    List<FieldViolation> errors = new ArrayList<>();
    if (p < 1) {
      errors.add(new FieldViolation("page", "out_of_range", "page must be at least 1."));
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      errors.add(
          new FieldViolation(
              "pageSize", "out_of_range", "pageSize must be between 1 and " + MAX_PAGE_SIZE + "."));
    }
    if (!errors.isEmpty()) {
      throw ProblemException.validation("The request contains invalid fields.", errors);
    }
    return new PageQuery(p, size);
  }

  /** Zero-based index of the first item of the page. */
  public long offset() {
    return (long) (page - 1) * pageSize;
  }

  /** The metadata of this page for {@code total} items. */
  public PageMeta meta(long total) {
    return new PageMeta(page, pageSize, total);
  }
}
