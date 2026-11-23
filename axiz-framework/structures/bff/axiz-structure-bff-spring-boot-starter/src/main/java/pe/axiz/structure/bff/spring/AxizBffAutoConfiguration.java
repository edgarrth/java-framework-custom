package pe.axiz.structure.bff.spring;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Estilo BFF: composición en paralelo consciente del contexto. Los clientes HTTP (token relay,
 * resiliencia), la caché de lectura y el manejo de canales provienen de los starters compuestos.
 */
@AutoConfiguration
public class AxizBffAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Compositions axizCompositions(Environment env) {
    ContextSnapshotFactory snapshots =
        ContextSnapshotFactory.builder().contextRegistry(ContextRegistry.getInstance()).build();
    // el binder de Boot convierte "500ms" o "3s" en cualquier contexto (Environment.getProperty
    // solo cuando el entorno lleva el servicio de conversión de Boot)
    Duration timeout =
        Binder.get(env)
            .bind("axiz.bff.timeout", Duration.class)
            .orElseGet(() -> Duration.ofSeconds(3));
    return new Compositions(snapshots, timeout);
  }
}
