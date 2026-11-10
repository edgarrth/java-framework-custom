package pe.axiz.spring.cache;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.core.cache.CacheKeys;
import pe.axiz.core.cache.CacheScope;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies {@link AxizCacheable}: the key is the cache name, the key format, the method signature
 * (or the explicit {@link AxizCacheable#keyName()}), the scope ({@link CacheScope}: the tenant by
 * default, the tenant and the caller, or nothing for global data) and the arguments encoded by
 * {@link CacheKeys}; values are stored as JSON with their full generic type; {@code null} results
 * are not cached; {@code Mono} and {@code Flux} results are cached when they complete, without
 * blocking the event loop, and their scope is read when they are subscribed. A call whose scope
 * cannot be resolved (a {@link CacheScope#CALLER} cache without an authenticated caller) runs
 * without the cache. The cache never replaces authorization: see {@link #ORDER}.
 */
public class AxizCacheableInterceptor implements MethodInterceptor {

  /**
   * Order of the advisor: inside every authorization interceptor of Spring Security method security
   * ({@code AuthorizationInterceptorsOrder}: {@code @PreFilter} 100 to {@code @PostFilter} 600), so
   * {@code @PreAuthorize}, {@code @Secured}, {@code @RolesAllowed}, {@code @PostAuthorize} and
   * {@code @PostFilter} run on every call, also when the value comes from the cache; and outside
   * {@code @Transactional} (lowest precedence), so a hit opens no transaction. An application that
   * moves method security with {@code @EnableMethodSecurity(offset = ...)} must keep this order
   * after its last authorization interceptor.
   */
  public static final int ORDER = 700;

  private static final boolean REACTOR = ClassUtils.isPresent("reactor.core.publisher.Mono", null);

  private final SingletonSupplier<AxizCaches> caches;
  private final SingletonSupplier<AsyncCacheSettings> asyncCaches;
  private final SingletonSupplier<JsonMapper> mapper;
  private final SingletonSupplier<@Nullable CacheCallerResolver> callers;
  private final Map<Method, Plan> plans = new ConcurrentHashMap<>();

  /** With the default caller identity ({@link DefaultCacheCallerResolver}). */
  public AxizCacheableInterceptor(Supplier<AxizCaches> caches, Supplier<JsonMapper> mapper) {
    this(caches, mapper, CacheScopes::defaultCallers);
  }

  /**
   * Creates the interceptor with the given caller identity for {@link CacheScope#CALLER} caches.
   *
   * @param callers identity of the caller for {@link CacheScope#CALLER} caches; a supplier that
   *     returns {@code null} (no Spring Security) leaves those caches unused
   */
  public AxizCacheableInterceptor(
      Supplier<AxizCaches> caches,
      Supplier<JsonMapper> mapper,
      Supplier<@Nullable CacheCallerResolver> callers) {
    this(
        caches,
        mapper,
        callers,
        () -> {
          throw new IllegalStateException(
              "Reactive caching requires native asynchronous cache settings");
        });
  }

  public AxizCacheableInterceptor(
      Supplier<AxizCaches> caches,
      Supplier<JsonMapper> mapper,
      Supplier<@Nullable CacheCallerResolver> callers,
      Supplier<AsyncCacheSettings> asyncCaches) {
    this.asyncCaches = SingletonSupplier.of(asyncCaches);
    this.caches = SingletonSupplier.of(caches);
    this.mapper = SingletonSupplier.of(mapper);
    this.callers = SingletonSupplier.of(callers);
  }

  AsyncCacheSettings asyncCaches() {
    return asyncCaches.obtain();
  }

  JsonMapper json() {
    return mapper.obtain();
  }

  @Nullable CacheCallerResolver callers() {
    return callers.get();
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Plan plan = plans.computeIfAbsent(invocation.getMethod(), this::plan);
    if (plan == null) {
      return invocation.proceed();
    }
    String arguments = CacheKeys.of(invocation.getArguments());
    if (REACTOR && plan.reactive() != null) {
      return ReactiveCaching.wrap(this, plan, arguments, invocation);
    }
    Optional<String> scope = CacheScopes.blocking(plan.scope(), callers());
    if (scope.isEmpty()) {
      return invocation.proceed();
    }
    String key = key(plan, scope.get(), arguments);
    Optional<Object> hit = read(plan, key, plan.valueType());
    if (hit.isPresent()) {
      return hit.get();
    }
    Object value = invocation.proceed();
    write(plan, key, value);
    return value;
  }

  static String key(Plan plan, String scope, String arguments) {
    return plan.keyPrefix() + scope + ":" + arguments;
  }

  Optional<Object> read(Plan plan, String key, JavaType type) {
    String full = caches.obtain().prefix() + ":" + key;
    return caches
        .obtain()
        .store()
        .get(full)
        .flatMap(
            bytes -> {
              try {
                return Optional.ofNullable(mapper.obtain().readValue(bytes, type));
              } catch (RuntimeException e) {
                caches.obtain().store().evict(full);
                return Optional.empty();
              }
            });
  }

  void write(Plan plan, String key, @Nullable Object value) {
    if (value == null) {
      return;
    }
    String full = caches.obtain().prefix() + ":" + key;
    caches.obtain().store().put(full, mapper.obtain().writeValueAsBytes(value), plan.ttl());
  }

  private @Nullable Plan plan(Method method) {
    AxizCacheable a = AnnotatedElementUtils.findMergedAnnotation(method, AxizCacheable.class);
    if (a == null) {
      return null;
    }
    String reactive = REACTOR ? ReactiveCaching.kind(method.getReturnType()) : null;
    Duration ttl =
        a.ttl().isBlank()
            ? (reactive == null
                ? caches.obtain().ttl(a.value())
                : asyncCaches.obtain().ttl(a.value()))
            : Duration.parse(a.ttl());
    Type generic = method.getGenericReturnType();
    Type valueType = generic;
    if (reactive != null && generic instanceof ParameterizedType p) {
      valueType = p.getActualTypeArguments()[0];
    }
    JavaType javaType = mapper.obtain().getTypeFactory().constructType(valueType);
    if ("flux".equals(reactive)) {
      javaType = mapper.obtain().getTypeFactory().constructCollectionType(List.class, javaType);
    }
    String name = a.keyName().isBlank() ? signature(method) : a.keyName();
    return new Plan(
        a.value(),
        a.value() + ":" + CacheKeys.FORMAT + ":" + name + ":",
        a.scope(),
        ttl,
        javaType,
        reactive);
  }

  /** Class, name and parameter types: overloads and equally named methods never share entries. */
  static String signature(Method method) {
    StringBuilder b =
        new StringBuilder(method.getDeclaringClass().getName())
            .append('#')
            .append(method.getName());
    b.append('(');
    Class<?>[] types = method.getParameterTypes();
    for (int i = 0; i < types.length; i++) {
      b.append(i == 0 ? "" : ",").append(types[i].getTypeName());
    }
    return b.append(')').toString();
  }

  record Plan(
      String cacheName,
      String keyPrefix,
      CacheScope scope,
      Duration ttl,
      JavaType valueType,
      @Nullable String reactive) {}
}
