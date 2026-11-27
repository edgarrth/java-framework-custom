# axiz-core-lang

Utilidades de lenguaje en Java puro: `Checks`, `Strings`, `Dates`, `ValueSnapshots` y `Result` (con `Ok` y `Err`).
Este módulo no depende de ningún otro módulo de Axiz y no tiene transporte, JSON, persistencia ni configuración de runtime.
Las anotaciones JSpecify expresan los contratos de nulos; el JDK aporta la implementación.

Se declara `pe.axiz:axiz-core-lang` directamente al importar `pe.axiz.core.lang`.
`axiz-core-commons` contiene los contratos de petición, página y cabeceras, la concurrencia y la infraestructura de transacciones.

`ValueSnapshots.map` separa de forma recursiva árboles de valores escalares, mapas y listas en
colecciones inmutables, conservando los nulos. Admite claves de texto, textos, booleanos y números
inmutables primitivos, BigDecimal y BigInteger; rechaza números no finitos, hojas mutables, claves
que no son texto y anidamientos de más de 64 niveles. No lee, no serializa, no hace I/O y no importa
ninguna librería JSON. Los modelos generados y los records de eventos lo reutilizan para mapas de
valores anidados; las colecciones no pueden retener estado mutable anidado de sus llamadores.
