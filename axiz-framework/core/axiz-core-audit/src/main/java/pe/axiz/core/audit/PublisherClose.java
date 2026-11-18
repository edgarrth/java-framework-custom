package pe.axiz.core.audit;

final class PublisherClose {
  private PublisherClose() {}

  static void close(Object delegate) {
    if (delegate instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted closing audit publisher", interrupted);
      } catch (Exception failure) {
        throw new IllegalStateException("Cannot close audit publisher", failure);
      }
    }
  }
}
