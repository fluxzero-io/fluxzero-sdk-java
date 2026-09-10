Use the reference graph only when an exact public SDK symbol, package, or advanced entry point is needed. Start with a
task article for semantics and examples; the reference confirms names and imports but does not make every public class
an application-level recommendation.

The raw SDK/common artifacts also contain implementation classes, protocol commands/results, decorators, caches, and
transport internals. Do not construct those merely because they are public to Java. Prefer:

1. static/high-level `Fluxzero` helpers;
2. typed gateways, repositories, stores, and builders exposed by the configured `Fluxzero` instance;
3. public operation clients only for the deliberate maintenance cases documented under application operations;
4. raw protocol payloads only inside framework/runtime development, not a Cloud application.

Use the public-symbol article for exact FQNs grouped by objective. If a symbol is absent there and no task article
links to it, inspect the SDK source and public contract before using it; do not guess a package from a similar name.
