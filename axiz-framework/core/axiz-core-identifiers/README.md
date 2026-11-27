<!-- generated -->
# axiz-core-identifiers

SPI genérica de identificadores con validadores (documentos nacionales, cuentas bancarias, tarjetas y datos de contacto).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-identifiers</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CheckDigits` | Algoritmos de dígito verificador compartidos por los tipos de identificador. |
| `Identifier` | Objeto de valor de identificador validado. |
| `IdentifierRegistry` | Registro de tipos de identificador: paquetes incluidos, proveedores por ServiceLoader y tipos propios. |
| `IdentifierType` | Interfaz de proveedor de servicio para cualquier tipo de identificador (documento, cuenta, tarjeta, contacto, etc.). |
| `IdentifierTypeProvider` | Se descubre con `java.util.ServiceLoader` para registrar paquetes de tipos de identificador. |
| `InvalidIdentifierException` | Se lanza cuando un valor no es un identificador válido del tipo solicitado. |
| `PeruIdentifiers` | Documentos nacionales peruanos: DNI, RUC y carné de extranjería (CE). |
| `ContactIdentifiers` | Datos de contacto: números de teléfono E.164 y correos electrónicos. |
| `BankingIdentifiers` | Identificadores bancarios: código de cuenta interbancario (CCI), número de tarjeta (PAN) e IBAN. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-identifiers verify`.
