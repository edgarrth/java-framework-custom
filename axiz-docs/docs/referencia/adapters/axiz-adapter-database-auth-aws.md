<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-database-auth-aws

Tokens de contraseña IAM de AWS RDS/Aurora que implementan `DatabasePasswordProvider` de
`axiz-kit-r2dbc`. El kit conserva el contrato neutral; este adaptador es dueño de la dependencia del
SDK de RDS y del firmante de tokens.

Este adaptador se declara con alcance `runtime` en los servicios que usan
`axiz.r2dbc.iam.enabled=true`. La capacidad R2DBC lo selecciona al generar un servicio; el starter
genérico mantiene opcional este adaptador IAM. Las librerías que construyen `RdsIamPasswordProvider`
directamente declaran, en cambio, una dependencia de compilación.

Spring prepara las credenciales y el primer token al arrancar. La generación de tokens usa la
instantánea de credenciales precalentada; las credenciales se refrescan con independencia de la
obtención de conexiones y fallan de forma cerrada al vencer. El pool resuelve un token por cada
conexión nueva. Los pools de lectura son dueños de sus recursos de token y los cierran al
destruirse. Las pruebas usan relojes y primitivas de sincronización inyectables.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-database-auth-aws</artifactId>
  <scope>runtime</scope>
</dependency>
```

La dependencia del adaptador AWS sobre el kit R2DBC es una excepción de arquitectura explícita,
porque implementa el contrato de credenciales de ese kit. No hace que el kit dependa de AWS.
