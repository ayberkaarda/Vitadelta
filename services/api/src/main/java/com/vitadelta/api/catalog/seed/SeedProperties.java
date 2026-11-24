package com.vitadelta.api.catalog.seed;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed settings.
 *
 * @param dataDir directory that holds {@code reference/}, {@code fixtures/} and {@code real/}
 */
@ConfigurationProperties(prefix = "vitadelta.seed")
public record SeedProperties(Path dataDir) {}
