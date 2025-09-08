## Installation

### Maven Users

Import the [Fluxzero BOM](https://mvnrepository.com/artifact/io.fluxzero/fluxzero-bom) in your
`dependencyManagement` section to centralize version management:

```xml

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.fluxzero</groupId>
            <artifactId>fluxzero-bom</artifactId>
            <version>${fluxzero.version}</version> <!-- See version badge above -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then declare only the dependencies you actually need (no version required):

```xml

<dependencies>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>java-client</artifactId>
    </dependency>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>java-client</artifactId>
        <classifier>tests</classifier>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>test-server</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>proxy</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

> 💡 **Note:** The `test-server` and `proxy` modules are **optional**, and can be included if you want to test your
> application locally against an **in-memory Flux server**.

---

### Gradle Users

Use [platform BOM support](https://docs.gradle.org/current/userguide/platforms.html) to align dependency versions
automatically:

<details>
<summary><strong>Kotlin DSL (build.gradle.kts)</strong></summary>

```kotlin
dependencies {
    implementation(platform("io.fluxzero:fluxzero-bom:${fluxzeroVersion}"))
    implementation("io.fluxzero:java-client")
    testImplementation("io.fluxzero:java-client", classifier = "tests")
    testImplementation("io.fluxzero:test-server")
    testImplementation("io.fluxzero:proxy")
}
```

</details>

<details>
<summary><strong>Groovy DSL (build.gradle)</strong></summary>

```groovy
dependencies {
    implementation platform("io.fluxzero:fluxzero-bom:${fluxzeroVersion}")
    implementation 'io.fluxzero:java-client'
    testImplementation('io.fluxzero:java-client') {
        classifier = 'tests'
    }
    testImplementation 'io.fluxzero:test-server'
    testImplementation 'io.fluxzero:proxy'
}
```

</details>

---

> 🔖 **Tip:** You only need to update the version number **once** in the BOM reference. All included modules will
> automatically align
> with it.