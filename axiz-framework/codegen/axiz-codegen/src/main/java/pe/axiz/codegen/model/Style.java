package pe.axiz.codegen.model;

import java.util.List;

/**
 * What the starter of an architectural style brings by itself, so that a generated service is
 * consistent with what actually starts: its migrations, properties and local resources come from
 * the same model as its dependencies.
 *
 * @param capabilities capabilities added to every service of the style whose store supports them
 *     (for example the inbox of an event processor: every consumer is de-duplicated)
 * @param jdbcTables structure tables of the stores the style starter wires on its own when the
 *     service has a JDBC database (servlet stack on Aurora): their DDL is emitted even without the
 *     capability
 */
public record Style(List<String> capabilities, List<String> jdbcTables) {

  /** Structure tables a migration can create: the SQL partials of the generator. */
  public static final List<String> TABLES =
      List.of("outbox", "inbox", "saga", "sequence", "shedlock");

  public static final Style NONE = new Style(List.of(), List.of());

  public Style {
    capabilities = List.copyOf(capabilities);
    jdbcTables = List.copyOf(jdbcTables);
  }
}
