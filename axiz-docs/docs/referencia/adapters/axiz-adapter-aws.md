<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-aws

Infraestructura de identidad y transporte de AWS sin dependencias de Micrometer ni de Spring.

`identity` contiene la política IRSA, recursos de credenciales con cierre uniforme e instantáneas
precalentadas. `transport` contiene los valores por defecto de los clientes del SDK y finalizaciones
asíncronas acotadas. La identidad no hace referencia al transporte. Los dueños de los recursos
liberan el `AwsCredentialSource` correspondiente; su método `provider()` entrega una vista que no
cierra. Las integraciones de observación y métricas las aporta `axiz-adapter-observability-aws` y
las compone Spring.

Se ejecuta `./mvnw -pl adapters/axiz-adapter-aws -am verify`.

Un recurso de credenciales siempre tiene `close()`, sea dueño de una fuente o la tome prestada.
Cerrar un recurso prestado invalida la vista de ese recurso y conserva el proveedor del dueño. Los
recursos propios cierran su proveedor una sola vez. La propiedad no se deduce del tipo en runtime de
un proveedor del SDK. Spring es dueño de una fuente por defecto, creada de forma diferida, por cada
contexto de aplicación, y entrega recursos independientes a los consumidores; los proveedores de
capacidad, servicio y aplicación conservan su precedencia.

El plugin de credenciales transfiere su recurso mediante un adaptador privado de proveedor del SDK
que se puede cerrar. El cliente del SDK cierra ese adaptador, que libera solo el recurso. Llamar a
`provider()` directamente deja la propiedad en el llamador; esa vista no puede cerrar la identidad
subyacente. Una construcción fallida de la fábrica también libera su recurso de credenciales y
conserva el fallo original.

## Seguridad del transporte

`AwsCredentialSources.create(TransportSecurityPolicy)` exige una política explícita. Los clientes
del framework usan HTTPS por defecto, validan los endpoints sobrescritos del SDK y comprueban la URI
efectiva antes de firmar y transmitir. Quien lo usa fuera del framework puede emplear
`TransportSecurityPolicy.secure()` o capturar la configuración explícita del proceso con `system()`.
HTTP requiere a la vez un ambiente local, test o ci y `axiz.transport.allow-plain-http=true`; la
validación de certificados sigue activa.

`AsyncAwsClients` acepta `AsyncAwsTransportSettings` con duraciones positivas de TTL y de inactividad
de las conexiones. El módulo base no lee propiedades de Spring.
