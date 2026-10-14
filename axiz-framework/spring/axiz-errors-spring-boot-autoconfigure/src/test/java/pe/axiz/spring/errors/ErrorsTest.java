package pe.axiz.spring.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ErrorCatalog;
import pe.axiz.core.errors.ErrorCode;
import pe.axiz.core.errors.Problem;

class ErrorsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
          .withPropertyValues(
              "axiz.errors.type-base-uri=https://errors.example.com/",
              "axiz.errors.catalog[0].code=PAY-ORDER-0001",
              "axiz.errors.catalog[0].category=UNPROCESSABLE",
              "axiz.errors.catalog[0].title=Insufficient funds");

  @Test
  void catalogFromConfigurationAndNoInternalMessages() {
    runner.run(
        ctx -> {
          assertTrue(
              ctx.getBean(ErrorCatalog.class).find(ErrorCode.of("PAY-ORDER-0001")).isPresent());
          ProblemResponder responder = ctx.getBean(ProblemResponder.class);
          Problem unexpected =
              responder.problem(
                  new IllegalStateException("password=123 leaked"),
                  Locale.ENGLISH,
                  "/payments",
                  "corr-12345678");
          assertEquals(500, unexpected.status());
          assertFalse(responder.json(unexpected).contains("password"));
          Problem conflict =
              responder.problem(
                  new BusinessException(CoreErrors.CONFLICT, "dup"),
                  Locale.ENGLISH,
                  "/payments",
                  null);
          assertEquals(409, conflict.status());
          assertEquals("https://errors.example.com/axz-core-0409", conflict.type());
          assertEquals(
              409,
              responder
                  .problem(
                      new DuplicateKeyException("uk_payment"), Locale.ENGLISH, "/payments", null)
                  .status());
        });
  }
}
