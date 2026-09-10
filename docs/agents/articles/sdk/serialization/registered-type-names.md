Use `io.fluxzero.common.serialization.RegisterType` when producers or JSON fixtures should use a stable short type
name instead of a Java fully qualified name. Annotate a message class or a root package and enable annotation
processing in every module contributing registered types.

```java
@RegisterType
public record ChangeLabel(String label) {}
```

```kotlin
@RegisterType
data class ChangeLabel(val label: String)
```

The examples assume the annotation import and the project's normal Kotlin/Jackson setup. A producer or fixture can
then refer to `ChangeLabel`. When simple names collide, use a distinguishing suffix such as `labels.ChangeLabel`.
Do not use an ambiguous simple name. FQNs remain valid for both registered and unregistered types.

## Where resolution applies

The generated registry resolves serialized envelope types for messages, documents, snapshots and other data, and
root/nested JSON `@class` values. Fixture JSON can therefore use a unique registered simple name:

```json
{"@class": "ChangeLabel", "label": "Primary"}
```

`Serializer.resolveTypeName` resolves aliases before registered names. Registered envelope names are normalized before
revision upcasters are selected. Historical aliases remain post-upcast so a caster for an old FQN still runs. Use
aliases for names already stored or queued after a package move; short names do not replace that historical migration.

## Preserve all generated indexes

The processor writes `META-INF/io.fluxzero.common.serialization.TypeRegistry`. Separate classpath entries and Spring
Boot nested JARs retain the contributing indexes. A custom uber-JAR must combine overlapping index resources rather
than keep only one module's file. For Maven Shade, configure an `AppendingTransformer` for this resource in the
application's own packaging configuration.

Java annotation processing and Kotlin kapt must run for each participating module. The `common` processor supplies
the registry; the SDK also supplies request, OpenAPI and web-parameter processors. Keep both required kapt artifacts
in Kotlin projects as described in project setup.

Verify simple names, suffix disambiguation, ambiguity rejection, nested JSON and the packaged multi-module artifact.
A successful IDE/test-classpath lookup alone does not prove the merged application JAR retains its indexes.
