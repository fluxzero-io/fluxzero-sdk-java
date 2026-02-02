# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Agent Instructions Release Artifacts**: GitHub releases now include `agents-java.zip` and `agents-kotlin.zip` containing AI coding assistant instructions for working with the Fluxzero SDK. These zip files provide guidelines, code samples, and FQN references to help AI tools generate correct Fluxzero code.

- **Host Metrics Collection**: New feature to collect and publish JVM and system metrics similar to Micrometer, without requiring Micrometer as a dependency. Enable with `FluxzeroBuilder.enableHostMetrics()`.

  **Collected metrics include:**
  - JVM Memory: heap/non-heap usage, memory pools (Eden, Survivor, Old Gen, Metaspace, etc.)
  - JVM GC: collection count and time per garbage collector
  - JVM Threads: thread count, daemon count, peak count, thread state distribution
  - JVM Classes: loaded/unloaded class counts
  - CPU: process/system CPU usage, available processors, load average, process CPU time
  - File Descriptors: open/max file descriptors (Unix only)
  - Uptime: JVM uptime and start time
  - Disk: total/free/usable space for configured paths (opt-in)
  - Container: cgroups v1/v2 CPU and memory limits/usage (auto-detected)

  **Usage:**
  ```java
  // Enable with defaults (30s collection interval)
  DefaultFluxzero.builder()
      .enableHostMetrics()
      .build(client);

  // Enable with custom configuration
  DefaultFluxzero.builder()
      .enableHostMetrics(config -> config
          .collectionInterval(Duration.ofMinutes(1))
          .applicationName("my-app")
          .collectDisk(true)
          .diskPaths(List.of(Path.of("/"))))
      .build(client);
  ```

  Metrics are published as a single `HostMetrics` event with metadata containing hostname, applicationName, and instanceId for multi-instance identification.
