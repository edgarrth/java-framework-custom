package pe.axiz.adapter.architecturefixture;

/** Fixtures distinguish an exported protocol leak from an internal SDK bridge. */
public final class AdapterApiFixture {
  private AdapterApiFixture() {}

  /** Invalid exported adapter signature. */
  public static final class Exposed {
    public void subscribe(org.reactivestreams.Subscriber<String> target) {}
  }

  private static final class Hidden {
    public void subscribe(org.reactivestreams.Subscriber<String> target) {}

    public static final class NestedHidden {
      public void subscribe(org.reactivestreams.Subscriber<String> target) {}
    }
  }

  public static Class<?> hidden() {
    return Hidden.class;
  }

  public static Class<?> nestedHidden() {
    return Hidden.NestedHidden.class;
  }
}
