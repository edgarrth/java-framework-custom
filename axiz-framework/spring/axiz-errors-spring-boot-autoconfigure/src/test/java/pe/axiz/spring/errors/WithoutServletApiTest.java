package pe.axiz.spring.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Los servicios reactivos no tienen la API de servlet en el classpath. Los mapeadores de
 * excepciones sirven a ambas pilas, por lo que deben funcionar cuando las clases {@code
 * jakarta.servlet} no pueden cargarse: una clase ausente dentro del manejador de errores deja al
 * cliente sin respuesta.
 */
class WithoutServletApiTest {

  @Test
  void exceptionsAreMappedWithoutTheServletApi() throws Exception {
    try (URLClassLoader loader = withoutServletApi()) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName("jakarta.servlet.ServletException", false, loader));
      Class<?> mappers = Class.forName(SpringExceptionMappers.class.getName(), true, loader);
      Method factory = mappers.getDeclaredMethod("springExceptions");
      factory.setAccessible(true);
      Object mapper = factory.invoke(null);
      Method map =
          loader.loadClass("pe.axiz.core.errors.ExceptionMapper").getMethod("map", Throwable.class);
      // una excepción que ningún mapeador conoce pasa por todas las comprobaciones, incluidas las
      // de servlet
      assertEquals(Optional.empty(), map.invoke(mapper, new IllegalStateException("boom")));
    }
  }

  /** El classpath de prueba, cargado de nuevo de forma aislada, sin {@code jakarta.servlet}. */
  private static URLClassLoader withoutServletApi() {
    URL[] urls =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(WithoutServletApiTest::url)
            .toArray(URL[]::new);
    return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("jakarta.servlet.")) {
          throw new ClassNotFoundException(name);
        }
        return super.loadClass(name, resolve);
      }
    };
  }

  private static URL url(String entry) {
    try {
      return Path.of(entry).toUri().toURL();
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }
}
