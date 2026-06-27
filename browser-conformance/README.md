# Browser Conformance

This module is the browser-native Fluxzero conformance harness. It is enabled explicitly with the
`browser-conformance` Maven profile so normal JVM SDK verification does not pick up Node or browser tooling.

Run:

```bash
./mvnw -Pbrowser-conformance -pl browser-conformance -am verify
```

Current coverage:

- A source-only Fluxzero app under `src/test/fluxzero` that uses the app-level feature surface the browser path must
  support.
- `sdk-browser-generator` checks that registry metadata lowers into browser Java source and a JS-readable manifest.
- Node tests assert the conformance feature matrix and `window.fluxzeroConformance` API contract.
- A Playwright acceptance file documents the browser app contract; wiring it to a TeaVM-built generated app is the next
  executable browser step.

The module intentionally does not run as part of default `clean install`.
