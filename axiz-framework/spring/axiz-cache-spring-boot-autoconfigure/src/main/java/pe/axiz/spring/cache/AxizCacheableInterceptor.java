package pe.axiz.spring.cache;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
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
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies {@link AxizCacheable}: the key is the cache name, the key format, the method signature
 * (or the explicit {@link AxizCacheable#keyName()}) and the arguments encoded by {@link CacheKeys};
 * values are stored as JSON with their full generic type; {@code null} results are not cached;
 * {@code Mono} and {@code Flux} results are cached when they complete, without blocking the event
 * loop.
 */
public class AxizCacheableInterceptor implements MethodInterceptor {

  private static final boolean REACTOR = ClassUtils.isPresent("reactor.core.publisher.Mono", null);

  private final SingletonSupplier<AxizCaches> caches;
  private final SingletonSupplier<JsonMapper> mapper;
  private final Map<Method, Plan> plans = new ConcurrentHashMap<>();

  public AxizCacheableInterceptor(Supplier<AxizCaches> caches, Supplier<JsonMapper> mapper) {
    this.caches = SingletonSupplier.of(caches);
    this.mapper = SingletonSupplier.of(mapper);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Plan plan = plans.computeIfAbsent(invocation.getMethod(), this::plan);
    if (plan == null) {
      return invocation.proceed();
    }
    String key = plan.keyPrefix() + CacheKeys.of(invocation.getArguments());
    if (REACTOR && plan.reactive() != null) {
      return ReactiveCaching.wrap(this, plan, key, invocation);
    }
    Optional<Object> hit = read(plan, key, plan.valueType());
    if (hit.isPresent()) {
      return hit.get();
    }
    Object value = invocation.proceed();
    write(plan, key, value);
    return value;
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
    Duration ttl = a.ttl().isBlank() ? caches.obtain().ttl(a.value()) : Duration.parse(a.ttl());
    Type generic = method.getGenericReturnType();
    String reactive = REACTOR ? ReactiveCaching.kind(method.getReturnType()) : null;
    Type valueType = generic;
    if (reactive != null && generic instanceof ParameterizedType p) {
      valueType = p.getActualTypeArguments()[0];
    }
    JavaType javaType = mapper.obtain().getTypeFactory().constructType(valueType);
    if ("flux".equals(reactive)) {
      javaType =
          mapper.obtain().getTypeFactory().constructCollectionType(java.util.List.class, javaType);
    }
    String name = a.keyName().isBlank() ? signature(method) : a.keyName();
    return new Plan(
        a.value(), a.value() + ":" + CacheKeys.FORMAT + ":" + name + ":", ttl, javaType, reactive);
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
      Duration ttl,
      JavaType valueType,
      @Nullable String reactive) {}
}
