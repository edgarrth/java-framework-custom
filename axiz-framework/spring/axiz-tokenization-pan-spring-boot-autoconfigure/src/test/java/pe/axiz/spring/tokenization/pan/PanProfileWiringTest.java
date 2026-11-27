package pe.axiz.spring.tokenization.pan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.pan.PanTokenProfile;
import pe.axiz.core.tokenization.vault.VaultOperations;

class PanProfileWiringTest {
  @Test
  void panAddsOnlyTheProfileWithCanonicalValidationAndAllowedMetadata() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTokenizationPanAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(PanTokenProfile.class)
                  .hasSingleBean(TokenProfile.class)
                  .doesNotHaveBean(VaultOperations.class);
              var profile = context.getBean(TokenProfile.class);
              assertThat(profile.id()).isEqualTo("pan.luhn");
              assertThat(profile.cardData()).isTrue();
              try (var input = SensitiveValue.of("4111 1111 1111 1111");
                  var canonical = profile.canonicalize(input)) {
                assertThat(canonical.reveal()).isEqualTo("4111111111111111");
                assertThat(profile.metadata(canonical))
                    .containsOnlyKeys("last4")
                    .containsEntry("last4", "1111");
              }
            });
  }
}
