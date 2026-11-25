package com.vitadelta.api.fx;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Schedule of {@link FxRefreshJob} (ADR-0009, ADR-0011): a fixed-rate task every {@code
 * vitadelta.fx.refresh-interval} (6 h) whose first execution happens when the scheduler starts.
 * That first execution applies the start-up rule ({@link FxRefreshJob#runIfDue()}: only when no
 * rate is stored or the newest is older than one day); every later one refreshes unconditionally.
 *
 * <p>Scheduling configurers are consulted only when scheduling is enabled, so with {@code
 * VITADELTA_JOBS_ENABLED=false} neither the start-up run nor the periodic runs happen; the admin
 * endpoint still refreshes on demand. The task runs on the scheduler thread pool and never delays
 * the application start.
 */
@Component
public class FxRefreshSchedule implements SchedulingConfigurer {

  private final FxRefreshJob job;
  private final FxProperties properties;
  private final AtomicBoolean firstExecution = new AtomicBoolean(true);

  public FxRefreshSchedule(FxRefreshJob job, FxProperties properties) {
    this.job = job;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedRateTask(this::tick, properties.refreshInterval());
  }

  /** One scheduled execution. */
  void tick() {
    if (firstExecution.getAndSet(false)) {
      job.runIfDue();
    } else {
      job.run();
    }
  }
}
