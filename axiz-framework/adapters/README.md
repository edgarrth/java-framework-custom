# adapters

Los adaptadores tecnológicos implementan los puertos de core o los contratos tecnológicos documentados. Los servicios los seleccionan en runtime; su código importa puertos y kits de persistencia, no las implementaciones de los adaptadores.

`axiz-adapter-database-auth-aws` implementa el contrato neutral `DatabasePasswordProvider` del kit
R2DBC. Su excepción de dependencias documentada mantiene el SDK de RDS en el adaptador, fuera del kit.

Se compila desde la raíz del framework con `./mvnw verify`. Las reglas de dependencias están en la guía de arquitectura del framework.
