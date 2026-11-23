package pe.axiz.adapter.architecturefixture;

import org.reactivestreams.Subscriber;

/** Los fixtures distinguen una fuga de protocolo exportada de un puente interno hacia el SDK. */
public final class AdapterApiFixture {
  private AdapterApiFixture() {}

  /** Firma de adaptador exportada inválida. */
  public static final class Exposed {
    public void subscribe(Subscriber<String> target) {}
  }

  private static final class Hidden {
    public void subscribe(Subscriber<String> target) {}

    public static final class NestedHidden {
      public void subscribe(Subscriber<String> target) {}
    }
  }

  public static Class<?> hidden() {
    return Hidden.class;
  }

  public static Class<?> nestedHidden() {
    return Hidden.NestedHidden.class;
  }
}
