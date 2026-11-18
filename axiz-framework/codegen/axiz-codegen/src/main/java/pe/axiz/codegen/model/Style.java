package pe.axiz.codegen.model;

import java.util.List;

/**
 * Capabilities selected for an architectural style, so that a generated service is consistent with
 * what actually starts: its migrations, properties and local resources come from the same model as
 * its dependencies.
 *
 * @param capabilities capabilities added to every service of the style whose store supports them
 *     (for example, inbox for event processors with persistence)
 */
public record Style(List<String> capabilities) {

  /** Structure tables a migration can create: the SQL partials of the generator. */
  public static final List<String> TABLES =
      List.of("outbox", "inbox", "saga", "sequence", "shedlock");

  public static final Style NONE = new Style(List.of());

  public Style {
    capabilities = List.copyOf(capabilities);
  }
}
