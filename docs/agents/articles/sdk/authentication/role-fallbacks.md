# Negative roles and role-specific query results

`@ForbidsAnyRole` excludes a caller with any listed role. It is not an anonymous-access annotation. With its default
`throwIfUnauthorized = true`, a missing user yields `UnauthenticatedException`; a caller with a forbidden role yields
`UnauthorizedException`. An authenticated caller with no forbidden role, including a roleless caller, passes this
negative check. Add a positive role requirement when roleless access is not allowed.

The attribute `throwIfUnauthorized` belongs on `@ForbidsAnyRole`/`@RequiresAnyRole`, not on `@HandleQuery`.
Keep the default throwing behavior for ordinary denial; silently making every candidate ineligible does not produce
a fallback result. This Java/Kotlin example assumes a configured `UserProvider` that resolves the authenticated caller:

```java
@Component
final class RestrictedRenderingEndpoint {
    @HandleQuery
    @ForbidsAnyRole("editor")
    String reader(RenderingChoice query) {
        return "read-only";
    }
}

record RenderingChoice() implements Request<String> {}
```

```kotlin
data object RenderingChoice : Request<String>

@Component
class RestrictedRenderingEndpoint {
    @HandleQuery
    @ForbidsAnyRole("editor")
    fun reader(query: RenderingChoice): String = "read-only"
}
```

If the same query must return different results by role, one authenticated handler with an explicit branch is a
clear alternative to competing silent-filter handlers:

```java
@Component
final class RenderingEndpoint {
    @HandleQuery
    @RequiresUser
    String choose(RenderingChoice query, User user) {
        return user.hasRole("editor") ? "editable" : "read-only";
    }
}
```

```kotlin
@Component
class RenderingEndpoint {
    @HandleQuery
    @RequiresUser
    fun choose(query: RenderingChoice, user: User): String =
        if (user.hasRole("editor")) "editable" else "read-only"
}
```

These are alternative endpoint designs, not two components to register together. Add a positive base-role gate if
roleless access is not permitted. Do not use independent consumers as an exclusive fallback chain: each group can
process the request separately. Use a distinct, explicitly public route if anonymous behavior is part of the product.

These annotations also work on payloads, classes, constructors and packages. A payload-level denial gates the
message before business handling; it is not a way to select between two handler methods. Method/type/package
precedence and `@NoUserRequired` overrides still apply. Do not combine `@NoUserRequired` with a role restriction
expecting both to be enforced: the no-user-required override disables the broader authorization requirement.

## Reusable enum-valued role annotations

The SDK supports `@ForbidsAnyRole` as a meta-annotation, including enum values exposed by the application's `value`
attribute. Their `toString()` values must match what `User.hasRole(String)` recognizes. A role enum exposed by a
public Java annotation must also be publicly accessible:

```java
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
@ForbidsAnyRole
public @interface ForbidsRole {
    Role[] value();
    boolean throwIfUnauthorized() default true;
}
```

```kotlin
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ForbidsAnyRole
annotation class ForbidsRole(
    vararg val value: Role,
    val throwIfUnauthorized: Boolean = true
)
```

For the default throwing check, assert forbidden-role denial, allowed role, roleless caller and genuinely absent
user separately. For the single-handler role branch, assert the exact result for each authenticated role and verify no
forbidden state changes/effects. The fixture's default system-user fallback can disguise absence: use the explicit
anonymous fixture setup from the authorization behavior matrix for that scenario. Changing a role in application
state must affect later requests through the configured provider rather than a stale constructed test user.
