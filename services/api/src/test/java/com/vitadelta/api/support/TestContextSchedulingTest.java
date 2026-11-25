package com.vitadelta.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Cached application contexts stay alive for the whole test JVM and share one database. A test
 * context therefore runs no scheduled job unless the test asks for it: a background run of another
 * cached context must never claim, lease or delete rows a test is working with.
 */
@SpringBootTest
class TestContextSchedulingTest extends PostgresIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void aTestContextRunsNoScheduledJobByDefault() {
    assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
  }
}
