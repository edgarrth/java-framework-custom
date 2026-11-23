<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-jpa-aws-spring-boot-autoconfigure

Failover del wrapper JDBC de AWS y autenticación IAM para JPA.

Este módulo es dueño de la API `pe.axiz.spring.jpa.aws`.

Se selecciona `axiz-jpa-aws-spring-boot-starter`; agrega la capacidad JPA neutral y el wrapper de AWS.

## Identidad de workload IAM

Cuando `axiz.jpa.aws-wrapper.iam=true`, el framework usa un perfil del wrapper sin credenciales con
failover2, EFM2, separación opcional de lectura y escritura y su plugin SPI de IAM. Cada pool Hikari
gestionado por el framework toma prestada una identidad del contexto mediante un recurso de credenciales.
Cada conexión o reconexión física firma un token nuevo para su host y usuario efectivos. No se reemplaza
ningún handler de credenciales global del proceso.

La configuración personalizada de `wrapperPlugins`, `wrapperProfileName` y `awsProfile` por conexión no se
combina con el IAM propio del framework; en ese caso la autenticación personalizada se configura
explícitamente. La autenticación estándar por contraseña sigue disponible cuando IAM está desactivado.
IRSA es exclusivo cuando se declara; otras cargas de trabajo usan la fuente por defecto del SDK,
incluida Pod Identity.

## Credenciales AWS de la aplicación

Con IAM del wrapper habilitado, un bean global `AwsCredentialsProvider`,
`@AwsIdentityFor(service = AwsService.RDS)` o
`@AwsIdentityFor(capability = AwsCapability.JPA)` aporta la identidad para las conexiones Hikari. El pool debe estar sin iniciar.
El puente asigna a cada pool un ID de scope opaco y su propio perfil de plugin del SDK, conservando el
failover, EFM y la separación opcional de lectura y escritura. Las propiedades de conexión siguen siendo
cadenas; el proveedor tipado se toma prestado mediante un registro con scope, porque el driver normaliza
los valores de las propiedades a texto. El handler global de credenciales del SDK nunca se reemplaza.

La destrucción del bean en Spring cierra el pool antes de eliminar su scope y su perfil. La destrucción del
processor también elimina los registros de los pools cuya inicialización falló. Un scope cerrado o
faltante falla de forma segura; nunca selecciona en silencio las credenciales del workload. El scope de
otro contexto no se ve afectado. La aplicación o Spring es responsable de cerrar el proveedor de la
aplicación; la limpieza de conexiones y perfiles no lo cierra. Sin un proveedor de la aplicación, el
contexto es dueño de una fuente por defecto; el pool cierra su recurso cuando se libera su scope, incluso
si la inicialización falló. Los fallos de base de datos o de STS y el reemplazo individual de conexiones no
cierran esa fuente compartida; su proveedor renueva las credenciales cuando hace falta.

Solo se inscriben los pools `axiz-jpa-iam` / `axiz-jpa-iam-split` propios del framework. Los pools externos conservan su propia política.
