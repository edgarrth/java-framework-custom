package pe.axiz.spring.security;

import java.util.Set;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.BooleanLiteral;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** Structural classification only: no evaluation context and no application bean invocation. */
final class AuthorizationExpressionClassifier {
  enum Access {
    FALSE(0),
    RESTRICTED(1),
    DYNAMIC(2),
    OPEN(3),
    TRUE(4);

    private final int permissiveness;

    Access(int permissiveness) {
      this.permissiveness = permissiveness;
    }

    Access and(Access other) {
      return permissiveness <= other.permissiveness ? this : other;
    }

    Access or(Access other) {
      return permissiveness >= other.permissiveness ? this : other;
    }

    boolean protectedAccess() {
      return permissiveness <= DYNAMIC.permissiveness;
    }
  }

  private static final Set<String> AUTHENTICATION =
      Set.of("isAuthenticated", "isFullyAuthenticated", "isRememberMe", "isAnonymous");
  private static final Set<String> AUTHORITIES =
      Set.of("hasAuthority", "hasAnyAuthority", "hasRole", "hasAnyRole");

  Access classify(String expression) {
    try {
      return classify(new SpelExpressionParser().parseRaw(expression).getAST(), false);
    } catch (UnsupportedAuthorizationExpression failure) {
      throw failure;
    } catch (ParseException failure) {
      throw new UnsupportedAuthorizationExpression(
          "Invalid authorization expression: " + expression);
    }
  }

  private Access classify(SpelNode node, boolean negated) {
    if (node instanceof OperatorNot) return classify(node.getChild(0), !negated);
    if (node instanceof OpAnd || node instanceof OpOr) {
      // Always inspect both operands, including unreachable unsupported expressions.
      Access left = classify(node.getChild(0), negated);
      Access right = classify(node.getChild(1), negated);
      boolean and = node instanceof OpAnd;
      return and != negated ? left.and(right) : left.or(right);
    }
    if (node instanceof BooleanLiteral) {
      boolean value = node.toStringAST().equals("true");
      return value != negated ? Access.TRUE : Access.FALSE;
    }
    if (node instanceof CompoundExpression) {
      if (node.getChildCount() == 1) return classify(node.getChild(0), negated);
      if (node.getChildCount() == 2
          && node.getChild(0) instanceof BeanReference
          && node.getChild(1) instanceof MethodReference method
          && validArguments(method)) return Access.DYNAMIC;
      throw unsupported(node);
    }
    if (node instanceof MethodReference method) {
      String name = method.getName();
      if (name.equals("permitAll") || name.equals("denyAll")) {
        if (method.getChildCount() != 0) throw unsupported(node);
        return name.equals("permitAll") != negated ? Access.TRUE : Access.FALSE;
      }
      if (AUTHENTICATION.contains(name) && method.getChildCount() == 0) return Access.OPEN;
      if (AUTHORITIES.contains(name)) {
        int count = method.getChildCount();
        if (count == 0 || ((name.equals("hasRole") || name.equals("hasAuthority")) && count != 1))
          throw unsupported(node);
        for (int i = 0; i < count; i++)
          if (!(method.getChild(i) instanceof StringLiteral)) throw unsupported(method.getChild(i));
        return negated ? Access.OPEN : Access.RESTRICTED;
      }
      if (name.equals("hasPermission")
          && (method.getChildCount() == 2 || method.getChildCount() == 3)
          && validArguments(method)) return Access.DYNAMIC;
    }
    throw unsupported(node);
  }

  private static boolean validArguments(SpelNode method) {
    for (int i = 0; i < method.getChildCount(); i++)
      if (!validArgument(method.getChild(i))) return false;
    return true;
  }

  private static boolean validArgument(SpelNode node) {
    if (node instanceof Literal || node instanceof VariableReference) return true;
    if (node instanceof PropertyOrFieldReference property)
      return Set.of("principal", "authentication").contains(property.getName());
    if (node instanceof CompoundExpression && node.getChildCount() > 1) {
      if (!(node.getChild(0) instanceof VariableReference)
          && !(node.getChild(0) instanceof PropertyOrFieldReference property
              && Set.of("principal", "authentication").contains(property.getName()))) return false;
      for (int i = 1; i < node.getChildCount(); i++)
        if (!(node.getChild(i) instanceof PropertyOrFieldReference)) return false;
      return true;
    }
    return false;
  }

  private static UnsupportedAuthorizationExpression unsupported(SpelNode node) {
    return new UnsupportedAuthorizationExpression(
        "Unsupported authorization node "
            + node.getClass().getSimpleName()
            + " at "
            + node.getStartPosition()
            + ": "
            + node.toStringAST());
  }
}
