# TeaVM Browser Compiler Spike

This experiment checks whether Java source can be compiled inside the browser and then executed as a TeaVM WebAssembly module.

It intentionally does not use Maven or Gradle for the application source path. The browser loads the `teavm-javac` compiler Wasm module, feeds it Java source text, generates an application Wasm module, and invokes `Main.main`.

The run script builds the real `common-api`, `sdk-api`, and `sdk-browser` modules and serves those jars as compiler classpath assets. It also bundles the browser runtime source files that TeaVM's browser-side compiler needs as source input.

## Run

```bash
experiments/teavm-browser-spike/run.sh
```

Then open the printed local URL.

The script copies the static spike files into `target/teavm-browser-spike` and downloads the public TeaVM playground compiler assets into `target/teavm-browser-spike/assets`.

## What This Proves

- Java source can be compiled in the browser without Maven, Gradle, or a server round trip.
- The browser compiler still contains OpenJDK `javac`, compiled to WebAssembly.
- TeaVM can then generate a runnable application WebAssembly module from the compiled bytecode.
- Fluxzero-shaped `CommandGateway` and `QueryGateway` facades can invoke statically registered handlers without runtime reflection.
- The generated dispatcher can stand in for Fluxzero registry output: it binds known payload types directly to known handler methods.
- Class-literal handler metadata can remain in user source while the compiler input is lowered to browser-safe annotations.

## Current Result

The default source bundle compiles fourteen Java source files:

- five user app files: `demo.Main`, three record payloads, and `demo.OrderHandlers`
- two browser-safe gateway contract files from `sdk-api`
- six browser gateway runtime files from `sdk-browser`
- one generated `demo.FluxzeroBrowserApplication` dispatcher

The mini registry discovers:

```text
registry.handlers        3
registry.commands        2
registry.queries         1
registry.allowedClasses  demo.CreateOrder, demo.CancelOrder, demo.GetOrderStatus
```

The default run prints:

```text
created: created 3 item(s) for order-100
cancelled: cancelled order-200 because duplicate
status: order-100 is locally visible
```

Observed local Chrome timings:

```text
compiler.load          ~122 ms
javac.wasm.compile      ~10 ms
teavm.generateWasm     ~185 ms
teavm.appWasm         ~22.6 KB
app.run                  ~3 ms
```

Warm edit in the same page:

```text
javac.wasm.compile      ~10 ms
teavm.generateWasm     ~185 ms
app.run                  ~3 ms
```

## What This Does Not Prove Yet

- That the full Fluxzero SDK is browser-safe.
- That browser compile latency and download size are acceptable for production.
- That annotation processors, reflection-heavy code, or arbitrary SDK extension points work in the browser.
- That annotation attributes using class literals work when passed directly to this browser compiler. The spike reads
  `@HandleCommand(allowedClasses = CreateOrder.class)` into registry metadata first, then lowers compiler input to bare
  `@HandleCommand` before invoking `teavm-javac`.

## Confirmed Language Notes

- Java records work for browser execution. The default `CreateOrder` command payload is a record.
- The generated dispatcher should use registry knowledge directly instead of runtime annotation reflection.
- Class-literal annotation metadata can be kept in user source and consumed by a registry pass before browser compilation.
