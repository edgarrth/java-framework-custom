<!-- generated -->
# axiz-core-sensitive

Niveles de sensibilidad, estrategias de enmascaramiento y anotaciones para respuestas, logs y auditoría.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-sensitive</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `JsonTextMasker` | Enmascara los valores de claves sensibles dentro de texto JSON sin procesar (cuerpos HTTP en logs) sin parsearlo. |
| `Mask` | Forma abreviada de un valor enmascarado de nivel `SensitivityLevel#MEDIUM` con el tipo de máscara indicado. |
| `MaskType` | Estrategias de enmascaramiento. |
| `Masker` | Funciones de enmascaramiento. |
| `MaskingPolicy` | Decide cómo se escribe un valor sensible en cada salida. |
| `ObjectMasker` | Convierte objetos en estructuras enmascaradas de tipo JSON (mapas, listas, textos, números) para logs y auditoría. |
| `OutputChannel` | Dónde se está escribiendo un valor. |
| `Sensitive` | Marca como sensible un campo, un componente de record o un getter. |
| `SensitiveField` | Metadatos de sensibilidad de una propiedad. |
| `SensitiveKeys` | Sensibilidad por nombre de propiedad o de clave JSON, para datos sin anotaciones (mapas, cuerpos HTTP sin procesar, cabeceras). |
| `SensitivityLevel` | Qué tan dañina sería la divulgación de un valor. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-sensitive verify`.
