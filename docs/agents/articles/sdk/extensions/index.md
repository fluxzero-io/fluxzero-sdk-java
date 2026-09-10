Fluxzero extension points customize dispatch, handler execution, tracker batches, parameter injection, validation,
serialization, and client construction. Use them only for cross-cutting application behavior that cannot be expressed
with ordinary handlers or configuration.

Choose the narrowest extension:

| Need | Extension |
| --- | --- |
| Add/inspect metadata before publication | `DispatchInterceptor` |
| Wrap one handler invocation or its result | `HandlerInterceptor` |
| Allocate/measure resources around a tracker batch | `BatchInterceptor` |
| Resolve a custom handler method parameter | `ParameterResolver` |
| Replace application-wide structural validation | `FluxzeroBuilder.replaceValidator(...)` |
| Change standard consumer templates or add a secondary consumer | `ConsumerConfiguration` on `FluxzeroBuilder` |

Do not use an interceptor to implement domain transitions, authorization that belongs in security/domain rules, or
transport protocols that already have gateways. Cross-cutting extensions participate in tests and replay, so their
ordering, determinism, error behavior, and registration must be explicit.

In Spring, centralize builder changes in one `FluxzeroCustomizer`. Outside Spring, configure one
`DefaultFluxzero.builder()` before building the application instance. Avoid static mutation after tracking starts.
