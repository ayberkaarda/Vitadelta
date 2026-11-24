package com.vitadelta.api.support;

import java.nio.file.Files;
import java.nio.file.Path;

/** Location of the repository's {@code data} directory, passed in by the Gradle test task. */
public final class TestData {

  private TestData() {}

  public static Path dataDir() {
    String dir = System.getProperty("vitadelta.test.data-dir");
    if (dir == null) {
      throw new IllegalStateException("system property vitadelta.test.data-dir is not set");
    }
    Path path = Path.of(dir);
    if (!Files.isDirectory(path)) {
      throw new IllegalStateException("data directory does not exist: " + path);
    }
    return path;
  }
}
