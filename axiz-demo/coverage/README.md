# Cobertura de las capacidades del framework

La [matriz de consumidores](CAPABILITIES.md) asocia cada capacidad del catálogo y cada variante
obligatoria de proveedor o de stack con un ejemplo ejecutable. La demo sigue siendo un consumidor:
ninguna lógica de negocio de la demo pertenece al framework.

`CapabilityCoverageTest` exige una capacidad declarada, un archivo fuente y un JUnit exacto
`fully.qualified.Class#method`. El XML de Surefire o Failsafe correspondiente debe contener un caso de
prueba exitoso y no omitido, y ser más reciente que las fuentes, los recursos y el POM del consumidor.
Una ejecución ausente, fallida, omitida o desactualizada hace fallar el build. `ExecutionEvidenceTest`
verifica esos caminos de rechazo, incluido el XML inseguro. Cada operación exitosa publica su capacidad
y sus variantes reales con `TestReporter` de JUnit; el Open Test Reporting estándar liga esas entradas a
la misma clase y método exitosos. Los starters del catálogo y las dependencias de proveedores deben
existir en el classpath de runtime registrado de esa prueba. Las etiquetas de stack de los servicios
deben coincidir con el manifiesto del consumidor. Cada plugin escribe su reporte de eventos en su
propio directorio, de modo que las pruebas de integración no pueden sobrescribir los resultados de las
pruebas unitarias. Los reportes ausentes o ambiguos hacen fallar el control. Las variantes se publican
después de que cada operación de proveedor o de stack comprobó su resultado.

El control también comprueba los JAR resueltos del framework contra los reportes y, cuando existe un
checkout adyacente del framework, sus fuentes, recursos y POM contra esos JAR. Los módulos modificados
del framework se instalan y la demo se vuelve a ejecutar: los reportes anteriores o un framework
instalado más antiguo no pueden demostrar un cambio nuevo. Son comprobaciones de reproducibilidad para
builds locales y de CI, no atestaciones firmadas a prueba de manipulación. Un comentario, una
dependencia o una propiedad de configuración por sí solos no pueden satisfacer este control.

El módulo de cobertura depende del POM de cada consumidor para el orden del reactor, con todas las
dependencias transitivas excluidas. Se ejecuta después de los servicios y de los ejemplos incluso en
un build paralelo de Maven. El reactor completo se ejecuta desde `axiz-demo`:

```bash
./mvnw clean verify -Pquality -Daxiz.it.requireDocker=true
```

`coverage.yaml` nombra la comprobación y su alcance:

- `integration`: infraestructura o transporte reales y efectos o resultados comprobados.
- `consumer-contract`: comportamiento ejecutable del consumidor con un colaborador controlado o un fixture de protocolo.
- `local-identity-contract`: comprobaciones locales de firma y configuración IAM; no demuestran la autorización en AWS.

`variants.yaml` añade las operaciones de proveedor obligatorias. Las alternativas de stack y de
persistencia se leen directamente del catálogo del framework, de modo que agregar una opción nueva al
catálogo no puede eludir el control omitiéndola del archivo de variantes de la demo. Estas
comprobaciones demuestran los ejemplos nombrados, no todas las combinaciones posibles de las opciones
del framework ni una certificación PCI DSS.

`provider-recipes` muestra proveedores alternativos y capacidades transversales mediante los starters
públicos. `protected-data` muestra las bóvedas nativas asíncronas e imperativas, operaciones
independientes de cifrado e índice, rotación administrativa y retención. Los servicios conservan los
flujos de negocio de pagos y sus pruebas de concurrencia e integridad. Todos los ejemplos usan
contenedores locales; las recetas IAM nunca contactan AWS remoto.

`sqs-consumer` aporta un classpath de starters independiente y mínimo. Su prueba de descubrimiento
automático ejecuta colas estándar y FIFO, reintentos, DLQ explícita y redrive del broker en ambos
stacks. Estas operaciones son entradas obligatorias de `variants.yaml`; los resultados solo del
publicador no pueden reemplazarlas.
