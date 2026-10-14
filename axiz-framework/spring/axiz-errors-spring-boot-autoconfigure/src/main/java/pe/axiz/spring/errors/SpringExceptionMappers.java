package pe.axiz.spring.errors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;
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

  private SpringExceptionMappers() {}

  static ExceptionMapper springExceptions() {
    return failure -> {
      Optional<AxizException> mapped = bindingErrors(failure);
      if (mapped.isPresent()) {
        return mapped;
      }
      if (VALIDATION && failure instanceof jakarta.validation.ConstraintViolationException cve) {
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
        if (failure instanceof org.springframework.security.access.AccessDeniedException) {
          return Optional.of(AccessException.forbidden("access denied"));
        }
        if (failure instanceof org.springframework.security.core.AuthenticationException) {
          return Optional.of(AccessException.unauthenticated("authentication failed"));
        }
      }
      if (DAO) {
        if (failure instanceof org.springframework.dao.OptimisticLockingFailureException
            || failure instanceof org.springframework.dao.DataIntegrityViolationException) {
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
    if (failure instanceof org.springframework.validation.BindingResult result) {
      return Optional.of(new ValidationException(violations(result)));
    }
    if (failure instanceof org.springframework.validation.BindException be) {
      return Optional.of(new ValidationException(violations(be.getBindingResult())));
    }
    return Optional.empty();
  }

  private static List<FieldViolation> violations(
      org.springframework.validation.BindingResult result) {
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
    if (failure
        instanceof
        org.springframework.web.method.annotation.HandlerMethodValidationException hmve) {
      List<FieldViolation> out = new ArrayList<>();
      hmve.getParameterValidationResults()
          .forEach(
              r ->
                  r.getResolvableErrors()
                      .forEach(
                          e ->
                              out.add(
                                  FieldViolation.of(
                                      r.getMethodParameter().getParameterName() == null
                                          ? "parameter"
                                          : r.getMethodParameter().getParameterName(),
                                      e.getCodes() == null || e.getCodes().length == 0
                                          ? "Invalid"
                                          : last(e.getCodes()),
                                      message(e.getDefaultMessage())))));
      return Optional.of(new ValidationException(out));
    }
    if (failure instanceof org.springframework.web.bind.MissingRequestHeaderException mh) {
      return Optional.of(
          new BusinessException(
              CoreErrors.MISSING_HEADER, "missing header", Map.of("header", mh.getHeaderName())));
    }
    if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
        || failure instanceof org.springframework.core.codec.DecodingException) {
      return Optional.of(new BusinessException(CoreErrors.MALFORMED_BODY, "unreadable body"));
    }
    if (failure instanceof org.springframework.web.ErrorResponse er) {
      int status = er.getStatusCode().value();
      ErrorCategory category = ErrorCategory.fromHttpStatus(status);
      return Optional.of(
          new AxizException(
              CoreErrors.forCategory(category), failure.getClass().getSimpleName(), failure));
    }
    return Optional.empty();
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
