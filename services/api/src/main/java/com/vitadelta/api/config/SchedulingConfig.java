package com.vitadelta.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} jobs only when {@code VITADELTA_JOBS_ENABLED} is true (the default).
 * With {@code false} (restore drill, seed run) no scheduled method runs. Every job wraps its run in
 * {@link com.vitadelta.api.common.jobs.JobLock} with its key from {@link
 * com.vitadelta.api.common.jobs.JobLockKeys}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "vitadelta.operations.jobs-enabled", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {}
