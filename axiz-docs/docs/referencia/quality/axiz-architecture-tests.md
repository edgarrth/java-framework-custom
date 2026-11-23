<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-architecture-tests

Pruebas ArchUnit del propio framework (`FrameworkArchitectureTest`): la capa 1 es Java simple (sin
Spring, Quarkus ni capas superiores), la capa 2 nunca depende de la capa 3, los módulos `-api` de los
frameworks dependen solo de la capa 1, cada starter usa solo su propia capacidad o el starter base, y
no hay ciclos entre capacidades del núcleo ni entre estilos. No se publica.
