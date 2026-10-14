package pe.axiz.spring.tenancy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Multi-tenancy ({@code axiz.tenancy.*}); only active when the service declares it.
 *
 * @param enabled whether the service is multi-tenant
 * @param header header carrying the tenant when it is trusted
 * @param trustHeader whether the header is trusted (only when set by the platform, never by
 *     clients)
 * @param required whether requests without a tenant are rejected
 * @param excludedPaths path patterns that need no tenant
 */
@ConfigurationProperties("axiz.tenancy")
public record AxizTenancyProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("X-Tenant-Id") String header,
    @DefaultValue("false") boolean trustHeader,
    @DefaultValue("true") boolean required,
    @DefaultValue({"/actuator/**", "/v3/api-docs/**", "/swagger-ui/**"})
        List<String> excludedPaths) {}
