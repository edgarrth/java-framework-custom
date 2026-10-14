# Feature flags

Flags definidos en configuración (ConfigMap del servicio, opcionalmente Parameter Store), evaluados
para un contexto: quién (llave de segmentación estable: usuario o cliente), por qué canal y para qué
tenant.

```yaml
axiz:
  feature-flags:
    flags:
      payments-usd:
        enabled: true
        percentage: 25          # despliegue gradual, estable por usuario
        channels: [mobile]      # vacío: cualquier canal
        tenants: []             # vacío: cualquier tenant
        rules:
          segment: [premium]    # atributo → valores permitidos
```

```java
EvaluationContext ctx = new EvaluationContext(customer.id(), "mobile", null, Map.of("segment", "premium"));
if (featureFlags.isEnabled("payments-usd", ctx)) { ... }
String layout = featureFlags.variant("home-layout", ctx, "classic");
```

- El porcentaje usa un hash estable de la llave: el mismo usuario ve siempre la misma variante.
- Cambiar un flag es cambiar el ConfigMap del ambiente (lo que dispara un rollout) o, para cambios
  en caliente, Parameter Store.
- Un flag es temporal: cuando la funcionalidad se estabiliza, se borra el flag y su código.

En el demo, `payments-experience` habilita pagos en dólares con `payments-usd`.
