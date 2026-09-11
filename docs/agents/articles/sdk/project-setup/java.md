Use this for Java source layout after `fz init --template flux-basic-java` has generated the project, or when validating an existing Java build. Treat the starter packages and handlers as replaceable scaffolding.

Recommended folders:

```text
src/main/java/com/example/app/App.java
src/main/java/com/example/app/package-info.java
src/main/java/com/example/app/<domain>/...
src/main/java/com/example/app/<domain>/api/...
src/main/java/com/example/app/<domain>/api/model/...
src/test/java/com/example/app/TestApp.java
src/test/java/com/example/app/<domain>/...
src/main/resources/fluxzero.properties
```

Spring Boot entry point:

```java
package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```

Fluxzero Spring configuration is auto-discovered by the SDK in normal Spring Boot apps. If a custom Spring setup does not load auto-configuration, import `FluxzeroSpringConfig` explicitly.

Package-level defaults:

```java
@RegisterType
@ApiDoc
@RequiresUser
@Path("/api")
package com.example.app;

import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.web.Path;
```

Notes:

- `@RegisterType` lets Fluxzero discover serializable payload types under the package.
- `@RequiresUser` secures payloads by default; add `@NoUserRequired` only on explicit public endpoints.
- `@Path("/api")` gives web handlers a package-level HTTP prefix. Use relative child `@Path` values to append to it; a leading slash resets the inherited prefix. Read web routing and parameter binding before defining nested paths.
- `@ApiDoc` opts the package's HTTP handlers into generated OpenAPI. Add `@ApiDocInfo` when document metadata or served OpenAPI/reference endpoints are required; read generated API discovery for flags, path resolution, operation IDs, and structural runtime tests.
- Use Java records for commands, queries, IDs, and value objects unless the domain needs a normal class.
- Use Lombok only if the project already wants it. Fluxzero itself does not require Lombok.
- Generate typed IDs at endpoint or command-factory boundaries before constructing commands such as `CreateUser`; do not generate IDs in `@Apply`.

Annotation processing must be enabled in the build. In Maven use `maven-compiler-plugin` with `io.fluxzero:sdk` in `annotationProcessorPaths`. In Gradle add both `annotationProcessor(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))` and `annotationProcessor("io.fluxzero:sdk")`; without the annotation-processor BOM or an explicit version, plain Gradle projects can fail to resolve the SDK processor.

For a new SDK v2 application, opt into its current defaults in `src/main/resources/fluxzero.properties`:

```properties
fluxzero.defaults.version=2026.09.10
```

Continue with the create-app recipe once the source layout compiles.

Preserve an existing application's defaults marker and explicit feature overrides unless changing them is part of
the requested migration. This marker enables Model RETRY defaults (2026.09.09) and automatic routing (2026.09.10);
the Model conflicts article explains their dedicated overrides and unchanged create-if-absent semantics.
