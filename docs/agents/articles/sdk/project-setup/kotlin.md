Use this for Kotlin source layout after `fz init --template flux-basic-kotlin` has generated the project, or when validating an existing Kotlin build. Treat the starter packages and handlers as replaceable scaffolding.

Before creating application files, read `/docs/sdk/project-setup/package-structure` for the domain-first rules,
a two-domain example tree and the final layout check. `<domain>` below is a business area, not a literal global package.

Recommended folders:

```text
src/main/kotlin/com/example/app/App.kt
src/main/java/com/example/app/package-info.java
src/main/kotlin/com/example/app/<domain>/...
src/main/kotlin/com/example/app/<domain>/api/...
src/main/kotlin/com/example/app/<domain>/api/model/...
src/test/kotlin/com/example/app/TestApp.kt
src/test/kotlin/com/example/app/<domain>/...
src/main/resources/fluxzero.properties
```

Kotlin still uses a small Java `package-info.java` when package-level annotations are useful:

```java
@RequiresUser
@Path("/api")
package com.example.app;

import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.web.Path;
```

Spring Boot entry point:

```kotlin
package com.example.app

import io.fluxzero.common.serialization.RegisterType
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@RegisterType(root = "com.example.app")
class App {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(App::class.java, *args)
        }
    }
}
```

Notes:

- Put `@RegisterType(root = "...")` on the Kotlin app class because Kotlin cannot conveniently express the same package annotation pattern as Java.
- Keep package-level security and web-path annotations in Java `package-info.java`.
- Use the Kotlin Spring plugin/all-open support so Spring can proxy components where needed.
- Add `jackson-module-kotlin` for JSON serialization of Kotlin classes.
- Enable kapt. Gradle templates use `kapt("io.fluxzero:common")`; Maven templates configure the Kotlin kapt execution with `io.fluxzero:sdk` as annotation processor path.
- Prefer Kotlin data classes for value objects and simple payloads, but keep Fluxzero command/query naming conventions the same as Java.
- For no-field query payloads, use a small placeholder such as a `Unit` default so serialization and request typing stay explicit.
- For Model shapes that need Java-style builder ergonomics, define that builder manually rather than relying on Lombok-style generation.
- Generate typed IDs before constructing commands, usually in an endpoint or command boundary, not in `@Apply`.

For a new application, opt into its current defaults in `src/main/resources/fluxzero.properties`:

```properties
fluxzero.defaults.version=2026.09.10
```

Continue with the create-app recipe once the source layout compiles.

Preserve an existing application's defaults marker and explicit feature overrides unless changing them is part of
the requested migration. This marker enables automatic Model routing (2026.09.10); Model RETRY is unconditional;
the Model conflicts article explains their dedicated overrides and unchanged create-if-absent semantics.
