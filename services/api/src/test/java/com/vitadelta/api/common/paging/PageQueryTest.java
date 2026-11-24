package com.vitadelta.api.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageQueryTest {

  @Test
  void defaultsAndOffsets() {
    PageQuery query = PageQuery.of(null, null);
    assertThat(query).isEqualTo(new PageQuery(1, 20));
    assertThat(query.offset()).isZero();
    assertThat(PageQuery.of(3, 100).offset()).isEqualTo(200);
    assertThat(PageQuery.of(2, 20).meta(41)).isEqualTo(new PageMeta(2, 20, 41));
  }

  @Test
  void invalidParametersAreValidationProblems() {
    assertThatThrownBy(() -> PageQuery.of(0, 101))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
              assertThat(e.status().value()).isEqualTo(422);
              assertThat(e.errors())
                  .extracting(FieldViolation::field)
                  .containsExactly("page", "pageSize");
              assertThat(e.errors()).extracting(FieldViolation::code).containsOnly("out_of_range");
            });
    assertThatThrownBy(() -> PageQuery.of(1, 0)).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> new PageQuery(0, 20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageMeta(1, 20, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void slicesInMemoryLists() {
    List<Integer> all = List.of(1, 2, 3, 4, 5);
    Page<Integer> second = Page.slice(all, new PageQuery(2, 2));
    assertThat(second.items()).containsExactly(3, 4);
    assertThat(second.meta()).isEqualTo(new PageMeta(2, 2, 5));
    assertThat(Page.slice(all, new PageQuery(3, 2)).items()).containsExactly(5);
    assertThat(Page.slice(all, new PageQuery(9, 2)).items()).isEmpty();
  }
}
