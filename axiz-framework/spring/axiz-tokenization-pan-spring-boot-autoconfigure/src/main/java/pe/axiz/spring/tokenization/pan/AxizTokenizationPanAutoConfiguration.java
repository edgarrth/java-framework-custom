package pe.axiz.spring.tokenization.pan;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.tokenization.pan.PanTokenProfile;

/** Registra el perfil PAN en el mismo motor genérico; sin almacén, claves ni motor separados. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration")
public class AxizTokenizationPanAutoConfiguration {
  @Bean
  PanTokenProfile axizPanTokenProfile() {
    return new PanTokenProfile();
  }
}
