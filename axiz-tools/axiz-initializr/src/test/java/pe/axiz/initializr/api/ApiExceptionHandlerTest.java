package pe.axiz.initializr.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      pe.axiz.initializr.service.PublicationException.Phase.class)
  void partialPublicationErrorsExposeOnlyStableRecoveryFields(
      pe.axiz.initializr.service.PublicationException.Phase phase) {
    var operation = java.util.UUID.randomUUID();
    var failure = org.mockito.Mockito.mock(pe.axiz.initializr.service.PublicationException.class);
    org.mockito.Mockito.when(failure.phase()).thenReturn(phase);
    org.mockito.Mockito.when(failure.operation()).thenReturn(operation);
    org.mockito.Mockito.when(failure.repository()).thenReturn("ana/orders");
    org.mockito.Mockito.when(failure.getMessage())
        .thenReturn("sensitive-provider-token\r\nforged-log");
    var problem = new ApiExceptionHandler().publicationIncomplete(failure);
    assertThat(problem.getStatus())
        .isEqualTo(
            phase == pe.axiz.initializr.service.PublicationException.Phase.CONFLICT ? 409 : 503);
    assertThat(problem.getProperties())
        .containsEntry("operationId", operation.toString())
        .containsEntry("phase", phase.name())
        .containsEntry("repository", "ana/orders");
    assertThat(problem.toString()).doesNotContain("sensitive-provider-token", "forged-log");
  }

  @Test
  void internalArgumentMessagesNeverBecomePublicDetails() {
    var problem =
        new ApiExceptionHandler()
            .badRequest(
                new IllegalArgumentException("CANARY credential=private-value path=/secrets/key"));
    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.toString()).doesNotContain("CANARY", "private-value", "/secrets/");
    assertThat(problem.getDetail()).isEqualTo("Invalid request parameters");
  }
}
