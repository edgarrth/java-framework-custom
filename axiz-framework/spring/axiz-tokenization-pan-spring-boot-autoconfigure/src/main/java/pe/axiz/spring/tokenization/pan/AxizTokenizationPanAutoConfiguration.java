package pe.axiz.spring.tokenization.pan;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.tokenization.pan.PanTokenProfile;

/** Registers the PAN profile in the same generic engine; no separate store, keys or motor. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration")
public class AxizTokenizationPanAutoConfiguration {
  @Bean
  PanTokenProfile axizPanTokenProfile() {
    return new PanTokenProfile();
  }
}
