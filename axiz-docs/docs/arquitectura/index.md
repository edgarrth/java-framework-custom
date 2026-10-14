# Arquitectura

axiz separa lo que es **Java puro** de lo que es **Spring** y de lo que es **estilo
arquitectónico**, para que cada pieza se pruebe sola, se pueda cablear con otro runtime y un
servicio dependa solo de lo que usa.

```mermaid
flowchart LR
  subgraph S["Servicio (arquetipo + estilo)"]
    code["Código del servicio"]
  end
  subgraph L3["Capa 3 · frameworks"]
    fw["layered · hexagonal · cqrs · eda · bff · batch"]
  end
  subgraph L2["Capa 2 · Spring Boot"]
    st["axiz-&lt;capacidad&gt;-spring-boot-starter"]
  end
  subgraph L1["Capa 1 · core"]
    core["axiz-core-&lt;capacidad&gt;[-&lt;tecnología&gt;]"]
  end
  P["Plataforma: parents y BOMs"]
  code --> fw --> st --> core
  P -.-> S & L3 & L2 & L1
```

- [Capas](capas.md): qué va en cada una y las reglas que lo verifican.
- [Repositorios y versiones](repositorios.md): monorepo del framework, parents, BOMs, ramas y releases.
- [Servlet y reactivo](stack.md): el stack como eje independiente del estilo.
- [Configuración y secretos](configuracion.md): ConfigMaps, Parameter Store y Secrets Manager.
- [Infraestructura AWS](aws.md): los servicios aprobados y su equivalente local.
- [Arquetipos](../arquetipos/index.md): el rol de cada servicio en el ecosistema.
