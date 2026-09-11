Use this when an HTTP request contains required fields, nested records, collections, or bulk items. Runtime validation and generated OpenAPI describe related but distinct contracts; every reachable DTO level must be correct.

| Annotation | Effect |
| --- | --- |
| `@Valid` | Cascades validation into a non-null value; it does not make the value, collection, or collection item required |
| `@NotNull`, `@NotBlank`, `@NotEmpty` | Enforces runtime shape and may inform OpenAPI when visible on the inspected record component |
| `@Size` | Enforces collection/string bounds and may document those bounds when visible |
| `@ApiDoc(required = true)` | Explicitly documents requiredness; it does not replace Jakarta runtime validation |

Validate the public DTO even when the command repeats the constraints. The command still needs protection for non-HTTP callers, while the endpoint DTO must reject malformed input before mapping and must advertise an accurate client contract.

## Model every nested level

```java
public record ImportRequest(
        @ApiDoc(required = true)
        @NotEmpty
        @Size(max = 100)
        List<@NotNull @Valid ArticleDraft> articles) {
}

public record ArticleDraft(
        @ApiDoc(required = true) @NotNull ArticleId articleId,
        @ApiDoc(required = true) @NotBlank String title,
        @ApiDoc(required = true) @NotNull LocalDate reviewAfter,
        @ApiDoc(required = true) @NotBlank String description,
        @ApiDoc(required = true) @NotNull Set<@NotBlank String> tags) {
}
```

`@Valid List<@Valid ArticleDraft> articles` alone is insufficient: `articles` may be null, the list may be empty, and a null element is not rejected by `@Valid`. Put container constraints on the record component and element constraints on the type argument.

If annotation propagation or processor visibility omits a required property, keep the Jakarta constraint and add `@ApiDoc(required = true)` at the inspected record component. The documentation annotation is a contract fallback, never a validation substitute.

## Verify routed validation and the served schema

Add routed scenarios for a valid request, omitted or null collection, empty collection, null list item, and one independently invalid nested field. Assert `ValidationException` and the intended path; do not let a downstream `NullPointerException` stand in for boundary validation.

Parse the served OpenAPI and follow every reachable `$ref`. For the example above, assert structurally and order-independently that:

- `ImportRequest.required` contains exactly `articles`;
- `articles.maxItems` is `100` and `articles.items.$ref` targets `ArticleDraft`;
- `ArticleDraft.required` contains `articleId`, `title`, `reviewAfter`, `description`, and `tags`;
- the operation request body references the expected outer schema.

Checking only that `requestBody.content.application/json.schema` exists can pass while every field remains optional. Likewise, checking only the outer required array misses an invalid nested item contract. Pair these contract assertions with the routed negative scenarios because OpenAPI presence does not prove runtime validation.
