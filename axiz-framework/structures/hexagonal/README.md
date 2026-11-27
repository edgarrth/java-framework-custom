# Estructura hexagonal

Los contratos neutrales y el cableado Spring están en sus módulos respectivos. CQRS es una extensión
opcional en `extensions/`, no una estructura separada.

## Política de dependencias hexagonal

El dominio admite el JDK, las anotaciones JSpecify y los paquetes de valores y contratos de Axiz
listados de forma explícita en `HexagonalRules` (commons, domain, money, errors, id, identifiers,
sensitive y time). Nunca depende de Spring ni de adaptadores de tecnología. El código de aplicación
usa puertos propios y contratos de Axiz; se admiten SLF4J y los contratos de publicador de Reactor
para logging y ejecución reactiva. La configuración es una raíz de composición externa. Los
adaptadores de entrada y de salida no pueden depender entre sí. Las firmas de los puertos no pueden
exponer modelos HTTP, clientes generados ni entidades de persistencia. Los controles de arquitectura
también siguen las dependencias a través de los tipos del núcleo de Axiz para detectar
infraestructura oculta.

Los servicios hexagonales y CQRS generados son dueños de modelos de aplicación simples en
`application.model`. Los modelos HTTP permanecen en `api.model`; la conversión ocurre en el adaptador
REST. La sincronización del contrato crea los modelos de aplicación nuevos y propone cambios a los
modelos existentes para su revisión. El generador admite esquemas de objeto explícitos y enums
compatibles con Java; los esquemas compuestos deben expresarse primero como contratos de aplicación
explícitos. La validación de esquemas permanece en el límite HTTP.

La `HandlerConfiguration` externa registra los handlers y las políticas de acceso de CQRS, lo que
mantiene los estereotipos de Spring fuera de la capa de aplicación.
