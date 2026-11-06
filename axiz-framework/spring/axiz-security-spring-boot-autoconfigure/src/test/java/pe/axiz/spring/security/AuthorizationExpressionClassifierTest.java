package pe.axiz.spring.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthorizationExpressionClassifierTest {
  static Stream<Arguments> expressions() {
    return Stream.of(
        Arguments.of("true", "TRUE"),
        Arguments.of("false", "FALSE"),
        Arguments.of("permitAll()", "TRUE"),
        Arguments.of("denyAll()", "FALSE"),
        Arguments.of("!permitAll()", "FALSE"),
        Arguments.of("not denyAll()", "TRUE"),
        Arguments.of("isAuthenticated()", "OPEN"),
        Arguments.of("not isAuthenticated()", "OPEN"),
        Arguments.of("isFullyAuthenticated()", "OPEN"),
        Arguments.of("!isFullyAuthenticated()", "OPEN"),
        Arguments.of("isRememberMe()", "OPEN"),
        Arguments.of("not isRememberMe()", "OPEN"),
        Arguments.of("isAnonymous()", "OPEN"),
        Arguments.of("!isAnonymous()", "OPEN"),
        Arguments.of("hasAuthority('admin')", "RESTRICTED"),
        Arguments.of("not hasAuthority('admin')", "OPEN"),
        Arguments.of("hasAnyRole('a', 'b')", "RESTRICTED"),
        Arguments.of("hasAnyAuthority('a', 'b')", "RESTRICTED"),
        Arguments.of("not (hasRole('A') and false)", "TRUE"),
        Arguments.of("not (hasRole('A') or true)", "FALSE"),
        Arguments.of("isAuthenticated() and hasAuthority('payments:write')", "RESTRICTED"),
        Arguments.of("@policy.check(#id) or true", "TRUE"),
        Arguments.of("not @policy.denied(#id)", "DYNAMIC"),
        Arguments.of(
            "@policy.check(#request.id, principal, authentication.name, null, 1, true)", "DYNAMIC"),
        Arguments.of("hasPermission(#id, 'read')", "DYNAMIC"),
        Arguments.of("hasPermission(#id, 'Payment', 'write')", "DYNAMIC"),
        Arguments.of("!!hasRole('A')", "RESTRICTED"),
        Arguments.of("false and @policy.check(#id)", "FALSE"));
  }

  @ParameterizedTest
  @MethodSource("expressions")
  void classifiesWithoutAnEvaluationContext(String expression, String expected) {
    assertEquals(expected, new AuthorizationExpressionClassifier().classify(expression).name());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "T(java.lang.System).exit(0)",
        "new java.lang.String('x')",
        "#x = true",
        "#x == 1",
        "#x ?: true",
        "#x ? true : false",
        "#x[0]",
        "#fn()",
        "hasAuthority(#authority)",
        "@policy.check(#x.toString())",
        "@policy.check(#x).allowed",
        "unknown()",
        "true or T(Foo)",
        "hasRole()",
        "hasAuthority('a', 'b')",
        "permitAll(true)",
        "hasPermission(#id)",
        "(",
        "#x.![id]",
        "#x.?[true]",
        "@policy.check(#x + 1)"
      })
  void rejectsNodesOutsideTheClosedGrammarEvenInUnreachableBranches(String expression) {
    assertThrows(
        UnsupportedAuthorizationExpression.class,
        () -> new AuthorizationExpressionClassifier().classify(expression));
  }
}
