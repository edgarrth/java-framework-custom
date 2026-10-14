<!-- generated -->
# axiz-core-sensitive

Sensitivity levels, masking strategies and annotations for responses, logs and audit.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-sensitive</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `JsonTextMasker` | Masks values of sensitive keys inside raw JSON text (HTTP bodies in logs) without parsing it. |
| `Mask` | Shorthand for a masked value of `SensitivityLevel#MEDIUM` with the given mask type. |
| `MaskType` | Masking strategies. |
| `Masker` | Masking functions. |
| `MaskingPolicy` | Decides how a sensitive value is written to each output. |
| `ObjectMasker` | Converts objects into masked, JSON-like structures (maps, lists, strings, numbers) for logs and audit. |
| `OutputChannel` | Where a value is being written. |
| `Sensitive` | Marks a field, record component or getter as sensitive. |
| `SensitiveField` | Sensitivity metadata of a property. |
| `SensitiveKeys` | Sensitivity by property or JSON key name, for data without annotations (maps, raw HTTP bodies, headers). |
| `SensitivityLevel` | How harmful the disclosure of a value would be. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-sensitive verify`.
