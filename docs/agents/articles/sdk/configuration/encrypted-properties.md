Use encrypted application properties when a value must be stored in a configuration file or supported configuration
source but must not appear there as plaintext. Prefer the Cloud product's supported secret/configuration facility when
it can supply the plaintext only at runtime; SDK encryption is an application-side option, not a replacement for
access control, rotation, or audit policy.

## Generate and protect the key

```java
String encryptionKey = DefaultEncryption.generateNewEncryptionKey();
```

The key has an algorithm-qualified form such as `ChaCha20|...`. Supply it through `ENCRYPTION_KEY` or the corresponding
supported application property source. Never commit it beside the ciphertext, print it, include it in MCP prompts, or
return it from an endpoint. Rotation requires re-encrypting stored values with the new key and deploying the matching
key through the supported Cloud path.

## Encrypt a value outside normal application execution

With the intended key active in the property source:

```java
String ciphertext = ApplicationProperties.encryptValue(secretValue);
```

Store only the complete `encrypted|<algorithm>|<ciphertext>` value:

```properties
integration.api-key=encrypted|ChaCha20|...
```

Reading it through `ApplicationProperties` is transparent:

```java
String apiKey = ApplicationProperties.requireProperty("integration.api-key");
```

Do not call `encryptValue(...)` during every startup and then overwrite deployment configuration. Treat encryption as
an explicit provisioning/rotation operation.

## Failure behavior and tests

A missing or incompatible key means the encrypted value cannot be decrypted; do not silently replace that with a
production-looking default. Fail application configuration validation before the first outbound request.

Tests should use a dedicated non-production key and verify:

- the persisted property starts with the encrypted envelope and does not contain the plaintext;
- the correctly configured key resolves the original value;
- a missing/wrong key makes required startup configuration fail;
- logs, metrics, generated docs, and exceptions do not expose key or plaintext.

Keep this article at the application configuration boundary. Database encryption, Kubernetes secrets, platform key
storage, and cluster rotation remain managed Cloud responsibilities unless a supported user-facing product operation
explicitly exposes them.
