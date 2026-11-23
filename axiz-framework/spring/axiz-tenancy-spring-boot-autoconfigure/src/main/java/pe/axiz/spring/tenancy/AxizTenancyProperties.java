package pe.axiz.spring.tenancy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Multi-tenancy ({@code axiz.tenancy.*}); solo está activo cuando el servicio lo declara.
 *
 * @param enabled indica si el servicio es multi-tenant
 * @param header cabecera que lleva el tenant cuando es de confianza
 * @param trustHeader indica si la cabecera es de confianza (solo cuando la establece la plataforma,
 *     nunca los clientes)
 * @param required indica si se rechazan las solicitudes sin tenant
 * @param excludedPaths patrones de ruta que no necesitan tenant
 */
@ConfigurationProperties("axiz.tenancy")
public record AxizTenancyProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("X-Tenant-Id") String header,
    @DefaultValue("false") boolean trustHeader,
    @DefaultValue("true") boolean required,
    @DefaultValue({"/actuator/**", "/v3/api-docs/**", "/swagger-ui/**"})
        List<String> excludedPaths) {
  public AxizTenancyProperties {
    excludedPaths = List.copyOf(excludedPaths);
  }
}
