# Response header buffer benchmark

This opt-in study runs a driver JVM separately from the proxy JVM. The proxy uses real SDK
forwarding with an asynchronous local SDK application in the same server JVM; it does not measure
runtime WebSocket delivery. Memory figures include that local application, not only the HTTP proxy.
The benchmark uses only local endpoints and does not force GC or native trimming.

The HTTP/1 growth path uses Jetty's public stream-customization API to preserve an
explicit `Connection: close` decision across Jetty 12.1's generator reset. HTTP/1.0 keep-alive,
error responses and WebSocket upgrades have focused contract coverage. The early-response test
for `Expect: 100-continue` does not establish header growth because the pool rounds up tiny
capacities. Other non-persistent decisions during actual header growth remain affected by
[Jetty #15840](https://github.com/jetty/jetty.project/issues/15840).

## Run

Use Java 25 and the Maven wrapper. Build test classes and a test dependency classpath:

```sh
./mvnw -B -pl proxy -am -DskipTests install
./mvnw -B -pl proxy dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/header-buffer-classpath.txt
java -Xms32m -Xmx384m -XX:+UseSerialGC -XX:NativeMemoryTracking=summary \
  -cp "proxy/target/test-classes:proxy/target/classes:$(cat proxy/target/header-buffer-classpath.txt)" \
  -DheaderBuffers=true -Doutput=/absolute/path/to/new-results \
  io.fluxzero.proxy.ProxyServerBenchmark
```

Use a new output directory for each run. The forked driver requires a real JVM classpath;
Maven `exec:java` uses a different classloader and is not supported for this mode.
The existing in-process benchmark remains available without `-DheaderBuffers=true`.

Requests are measured in completed batches of at least 32 (or the concurrency, if larger).
Workers are started once per scenario and reused outside batch timing. Between batches the
driver expires the local WEBREQUEST/WEBRESPONSE logs without resetting their indices after all responses
have completed. This avoids measuring unbounded in-memory runtime history; no GC is forced.
Clearing/control waits are excluded from throughput time, while one-second memory samples
continue. These are batched transport measurements, not an end-to-end sustained throughput SLA.

The driver verifies that it and every child use
`-Xms32m -Xmx384m -XX:+UseSerialGC -XX:NativeMemoryTracking=summary`; each child also gets its own GC log.
Only the initial buffer, output-buffer placement and instrumentation flag change between children.
All children retain the default 1 MiB header maximum. The driver allows 2 MiB response headers so
its own lower header limit does not truncate the experiment. Failures and HTTP/2 fallback abort
the run.

Parameters (driver system properties):

| Property | Default | Meaning |
| --- | --- | --- |
| `buffers` | `4096,8192,16384,32768,65536,1048576` | Initial capacities, bytes |
| `outputDirect` | `true` | `true`, `false` or both, to compare direct and heap HTTP output buffers |
| `instrument` | `false,true` | Separate performance and instrumented child runs |
| `protocols` | `h1,h2` | HTTP/1.1 and cleartext HTTP/2 |
| `connections` | `keep-alive,close` | Reuse versus close/fresh connections |
| `concurrencies` | `1,16` | Driver workers |
| `requests` | `1000` | Measured requests per scenario |
| `warmup` | `100` | Untimed requests per scenario |
| `probeSeconds` | `60` | Additional probes: health every 10 s, ready every 2 s, then a separate readiness-503 phase every 2 s; 0 skips |
| `scenarios` | all below | Comma-separated scenarios |
| `output` | `target/header-buffer-benchmark` | Output directory |

Scenarios: `health`, `ready`, `not-ready` (503), `small`, `large-body` (2 MiB body),
`below` (initial capacity minus 512 header-value bytes), `above` (initial plus 512, capped below
the maximum), `near-max`, `mixed-1`, `mixed-10`, `mixed-100`. Mixed scenarios use 66048-byte
header values for exactly the indicated percentage of each 100 requests; use request counts
that are multiples of 100. Other responses have a 16-byte header value. Values exclude the
HTTP status line, field names and other headers. `above` cannot exceed the initial 1 MiB
baseline while remaining valid, so its baseline is deliberately capped.

HTTP/2 forbids `Connection: close`. For `h2/close` the driver creates a new client, primes an
HTTP/2 connection with a health request, sends the measured request and closes the client.
Its timings and acquisitions include this extra work. Do not compare that row as if it were
a single HTTP/1 close request. Persistent HTTP/2 scenarios are primed by warmup.

## Read the results

- `runs.csv`: throughput and p50/p95/p99 latency, with instrumentation explicitly identified.
  Instrumented HTTP/1 rows also report initial header acquisitions and overflow acquisitions;
  `-1` means not measured/applicable. HTTP/2 uses its own header encoding path.
- `memory.csv` per child: one-second server-JVM samples (direct capacity/used bytes, heap, GC,
  Linux RSS and container usage/limit). `-1` means unavailable. Samples are not peak guarantees.
- Before/after `.properties`: cumulative acquisition counters, memory, JVM arguments and PID.
  Subtract matching snapshots; do not compare cumulative end values across scenarios.
- `*-nmt.txt`: raw NMT summaries outside timed request phases. `Other` is not all native RSS.
- `gc.log`, `proxy.log`, `command.txt`: GC events, child diagnostics and exact launch arguments.

`acquire.http1-send/direct/<bytes>` and `acquire.http1-send/heap/<bytes>` identify acquisitions
from the HTTP/1 send generator.
The generator also requests 12-byte chunk buffers, so those are recorded separately.
For ordinary successful responses with an initial capacity below the maximum, acquisitions
at the maximum identify header overflow retries. This interpretation does not apply to the
1 MiB baseline (its initial allocation is already at the maximum), HTTP/2 or arbitrary
trailer/error traffic. All other acquisitions are retained under `acquire.other`.
Acquisitions count pool requests, **not necessarily new native allocations**. Outstanding
buffer counts include idle connections' input buffers until those connections close.

Instrumentation walks the stack and wraps releases. Use only `instrument=false` rows for
performance claims. Repeat uninstrumented runs, alternate capacity order, and increase warmup
before drawing latency conclusions. Shared child scenarios preserve allocator history;
use `scenarios` to run a single scenario in fresh children when isolating retained memory.

On Linux, keep the driver outside the proxy's memory-limited container when evaluating memory
pressure. Cgroup values from a shared container include other processes and must not be called
proxy-only usage. Record image digest, architecture, allocator, CPU/memory limits and JVM
version. Exceeding a container limit is an optional diagnostic outcome, never a required
regression gate.

For historical 1.x/2.0 baselines, keep their JVM/load settings identical and report their
actual supported endpoints and protocol. These new test helpers are not binary compatible
with historical SDKs: use a version-specific harness, rather than mixing historical jars
into the current test classpath. Keep historical measurements outside the source tree.
