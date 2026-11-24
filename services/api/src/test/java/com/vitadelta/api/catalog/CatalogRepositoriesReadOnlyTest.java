package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;

/**
 * Catalog repositories are read-only: every write must go through {@link CatalogWriter} inside
 * {@link CatalogWriteTransaction}, which bumps the catalog version. A repository that inherits
 * {@code save}, {@code delete*} or similar methods would allow a write without the bump.
 */
class CatalogRepositoriesReadOnlyTest {

  private static final Set<String> READ_PREFIXES = Set.of("find", "count", "exists");

  static List<Class<?>> catalogRepositories() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
            return definition.getMetadata().isInterface();
          }
        };
    scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
    List<Class<?>> result = new ArrayList<>();
    for (BeanDefinition definition :
        scanner.findCandidateComponents(CatalogWriter.class.getPackageName())) {
      result.add(Class.forName(definition.getBeanClassName()));
    }
    return result;
  }

  @Test
  void everyCatalogRepositoryIsFound() throws ClassNotFoundException {
    assertThat(catalogRepositories())
        .extracting(Class::getSimpleName)
        .containsExactlyInAnyOrder(
            "NutrientRepository",
            "ReferenceSetRepository",
            "UlSetRepository",
            "ReferenceValueRepository",
            "UpperLimitRepository",
            "UnitConversionRepository",
            "CountryRepository",
            "ProductRepository",
            "ProductNutrientRepository",
            "ProductAvailabilityRepository",
            "OfferRepository",
            "CatalogVersionRepository");
  }

  @Test
  void catalogRepositoriesExposeNoMutatingMethods() throws ClassNotFoundException {
    for (Class<?> repository : catalogRepositories()) {
      for (Method method : repository.getMethods()) {
        assertThat(READ_PREFIXES.stream().anyMatch(method.getName()::startsWith))
            .as(repository.getSimpleName() + "." + method.getName())
            .isTrue();
      }
    }
  }
}
