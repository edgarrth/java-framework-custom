<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-money

Objeto de valor de dinero con monedas ISO 4217, redondeo y aritmética segura; puerto de tipo de cambio.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-money</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CurrencyConverter` | Convierte importes con un `ExchangeRateProvider` y prueba la tasa inversa como respaldo. |
| `ExchangeRate` | Tipo de cambio vigente en un instante: `1 from = rate to`. |
| `ExchangeRateProvider` | Puerto para obtener tipos de cambio; lo implementa el servicio (tabla, caché, API externa). |
| `Money` | Importe monetario en una moneda ISO 4217. |
| `MoneyErrors` | Errores de la capacidad de dinero. |

## Importes exactos y redondeo explícito

`Money.of` y `Money.parse` aceptan importes externos de forma exacta; rechazan el exceso de decimales
en lugar de redondear. Para valores calculados se usa `Money.rounded(amount, currency, mode)`.
`multiply`, `divide`, `percentage`, la conversión de moneda y `CurrencyConverter.convert` exigen un
`RoundingMode` explícito; el framework no elige una política de redondeo financiero.

```java
Money amount = Money.of("150.50", "PEN");
Money tax = amount.percentage(new BigDecimal("18"), RoundingMode.HALF_UP);
```

`CurrencyConverter` prefiere una cotización directa disponible. Su respaldo inverso divide el
importe entre la cotización opuesta y redondea una sola vez en la escala de la moneda destino.
Nunca construye un recíproco aproximado antes de convertir un pago. `ExchangeRate.convertInverse`
expone esa misma operación directamente. `ExchangeRate.inverse(scale, mode)` representa una tasa
aproximada para mostrar o guardar; para convertir un importe en sentido contrario se usa
`convertInverse`. Las tasas tienen los mismos límites de dígitos enteros y de escala que los
operandos monetarios antes de cualquier división inversa, de modo que exponentes extremos no
provocan una expansión decimal ilimitada. Los operandos externos mantienen el límite de 100
decimales. Los productos internos admiten hasta 106 decimales (100 del operando, 4 de la moneda y 2
del desplazamiento del porcentaje), con un solo redondeo antes de comprobar los límites del importe
final. La escala de moneda soportada va de 0 a 4; las pseudomonedas usan el respaldo explícito de 2
decimales. Se rechazan las escalas de moneda no soportadas. Estos límites internos no amplían la
API pública ni relajan los límites de lectura y de las fábricas.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-money verify`.
