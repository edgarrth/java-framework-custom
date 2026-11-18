package pe.axiz.spring.errors;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.codec.DecodingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.MissingRequestValueException;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ExceptionMapper;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;

/**
 * Translates framework exceptions (Spring MVC/WebFlux, Bean Validation, Spring Security, Spring
 * data access) into axiz errors. Optional libraries are detected by class name.
 */
final class SpringExceptionMappers {

  private static final boolean WEB = present("org.springframework.web.ErrorResponse");
  private static final boolean VALIDATION =
      present("jakarta.validation.ConstraintViolationException");
  private static final boolean SECURITY =
      present("org.springframework.security.access.AccessDeniedException");
  private static final boolean DAO = present("org.springframework.dao.DataAccessException");
  // Spring MVC's binding exceptions extend jakarta.servlet.ServletException: a reactive service
  // has no servlet API, so they are only referenced when it is present
  private static final boolean SERVLET = present("jakarta.servlet.ServletException");

  private SpringExceptionMappers() {}

  /** MVC advice must not turn an anonymous method-security denial into an authenticated 403. */
  static Throwable servletSecurityFailure(Throwable failure) {
    if (SECURITY && failure instanceof AccessDeniedException) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null
          && !new AuthenticationTrustResolverImpl().isAuthenticated(authentication))
        return AccessException.unauthenticated("authentication required");
    }
    return failure;
  }

  static ExceptionMapper springExceptions() {
    return failure -> {
      Optional<AxizException> mapped = bindingErrors(failure);
      if (mapped.isPresent()) {
        return mapped;
      }
      if (VALIDATION && failure instanceof ConstraintViolationException cve) {
        List<FieldViolation> violations = new ArrayList<>();
        cve.getConstraintViolations()
            .forEach(
                v ->
                    violations.add(
                        FieldViolation.of(
                            String.valueOf(v.getPropertyPath()),
                            v.getConstraintDescriptor()
                                .getAnnotation()
                                .annotationType()
                                .getSimpleName(),
                            v.getMessage())));
        return Optional.of(new ValidationException(violations));
      }
      if (SECURITY) {
        if (failure instanceof AccessDeniedException) {
          return Optional.of(AccessException.forbidden("access denied"));
        }
        if (failure instanceof AuthenticationException) {
          return Optional.of(AccessException.unauthenticated("authentication failed"));
        }
      }
      if (DAO) {
        if (failure instanceof OptimisticLockingFailureException
            || failure instanceof DataIntegrityViolationException) {
          return Optional.of(
              new BusinessException(CoreErrors.CONFLICT, failure.getClass().getSimpleName()));
        }
      }
      if (WEB) {
        return webErrors(failure);
      }
      return Optional.empty();
    };
  }

  private static Optional<AxizException> bindingErrors(Throwable failure) {
    if (failure instanceof BindingResult result) {
      return Optional.of(new ValidationException(violations(result)));
    }
    if (failure instanceof BindException be) {
      return Optional.of(new ValidationException(violations(be.getBindingResult())));
    }
    return Optional.empty();
  }

  private static List<FieldViolation> violations(BindingResult result) {
    List<FieldViolation> out = new ArrayList<>();
    result
        .getFieldErrors()
        .forEach(
            fe ->
                out.add(
                    new FieldViolation(
                        fe.getField(),
                        fe.getCode() == null ? "Invalid" : fe.getCode(),
                        message(fe.getDefaultMessage()),
                        null)));
    result
        .getGlobalErrors()
        .forEach(
            ge ->
                out.add(
                    FieldViolation.of(
                        ge.getObjectName(),
                        ge.getCode() == null ? "Invalid" : ge.getCode(),
                        message(ge.getDefaultMessage()))));
    return out;
  }

  private static Optional<AxizException> webErrors(Throwable failure) {
    if (failure instanceof HandlerMethodValidationException hmve) {
      List<FieldViolation> out = new ArrayList<>();
      hmve.getParameterValidationResults()
          .forEach(
              r -> {
                String parameter = r.getMethodParameter().getParameterName();
                r.getResolvableErrors()
                    .forEach(
                        e -> {
                          String[] codes = e.getCodes();
                          out.add(
                              FieldViolation.of(
                                  parameter == null ? "parameter" : parameter,
                                  codes == null || codes.length == 0 ? "Invalid" : last(codes),
                                  message(e.getDefaultMessage())));
                        });
              });
      return Optional.of(new ValidationException(out));
    }
    if (SERVLET) {
      Optional<AxizException> servlet = ServletErrors.map(failure);
      if (servlet.isPresent()) {
        return servlet;
      }
    }
    if (failure instanceof MissingRequestValueException mv && "header".equals(mv.getLabel())) {
      return Optional.of(missingHeader(mv.getName()));
    }
    if (failure instanceof HttpMessageNotReadableException
        || failure instanceof DecodingException) {
      return Optional.of(new BusinessException(CoreErrors.MALFORMED_BODY, "unreadable body"));
    }
    if (failure instanceof ConversionNotSupportedException) return Optional.empty();
    if (failure instanceof TypeMismatchException mismatch) {
      String field =
          mismatch instanceof MethodArgumentTypeMismatchException argument
              ? argument.getName()
              : "parameter";
      return Optional.of(
          new ValidationException(
              List.of(FieldViolation.of(field, "TypeMismatch", "invalid value"))));
    }
    if (failure instanceof ErrorResponse er) {
      int status = er.getStatusCode().value();
      ErrorCategory category = ErrorCategory.fromHttpStatus(status);
      return Optional.of(
          new AxizException(
              CoreErrors.forCategory(category),
              // logged as the reason of the problem: "NoResourceFoundException: No static resource"
              failure.getClass().getSimpleName(),
              failure));
    }
    return Optional.empty();
  }

  private static AxizException missingHeader(String header) {
    return new BusinessException(
        CoreErrors.MISSING_HEADER, "missing header", Map.of("header", header));
  }

  /** Servlet-only exceptions, loaded only when the servlet API is on the classpath. */
  private static final class ServletErrors {
    private ServletErrors() {}

    static Optional<AxizException> map(Throwable failure) {
      // Optional container type: malformed query/form encoding fails before Spring binding.
      // Do not map arbitrary IllegalStateException/IllegalArgumentException or reflect the input.
      if (failure
          .getClass()
          .getName()
          .equals("org.apache.tomcat.util.http.InvalidParameterException")) {
        return Optional.of(
            new ValidationException(
                List.of(
                    FieldViolation.of("parameters", "Malformed", "invalid request parameters"))));
      }
      if (failure instanceof MissingRequestHeaderException mh) {
        return Optional.of(missingHeader(mh.getHeaderName()));
      }
      return Optional.empty();
    }
  }

  private static String last(String[] codes) {
    String c = codes[codes.length - 1];
    return c == null ? "Invalid" : c;
  }

  private static String message(@Nullable String m) {
    return m == null ? "invalid value" : m;
  }

  private static boolean present(String className) {
    return ClassUtils.isPresent(className, SpringExceptionMappers.class.getClassLoader());
  }
}
