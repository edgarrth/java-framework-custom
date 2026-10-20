package pe.axiz.cli;

import java.util.concurrent.Callable;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Framework;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** {@code axiz catalog [archetypes|capabilities]}: what can be generated and added. */
@Command(
    mixinStandardHelpOptions = true,
    name = "catalog",
    description = "Lists archetypes and capabilities.")
class CatalogCommand extends BaseCommand implements Callable<Integer> {

  @Parameters(arity = "0..1", defaultValue = "all", description = "archetypes, capabilities or all")
  String what = "all";

  @Override
  public Integer call() {
    Catalog catalog = Catalog.load();
    if (!what.equals("capabilities")) {
      out().println("Archetypes (first option is the default):");
      for (Archetype a : catalog.archetypes()) {
        out().printf("  %-16s %s%n", a.name(), a.role());
        if (a.isService()) {
          out()
              .printf(
                  "  %-16s framework %s / stack %s / persistence %s%n",
                  "",
                  String.join("|", a.frameworks().stream().map(Framework::id).toList()),
                  String.join("|", a.stacks().stream().map(s -> s.id()).toList()),
                  a.persistence().isEmpty() ? "none" : String.join("|", a.persistence()));
        }
      }
    }
    if (!what.equals("archetypes")) {
      out().println("Capabilities (axiz add <name>):");
      for (Capability c : catalog.capabilities()) {
        out().printf("  %-22s %s%n", c.name(), c.description());
      }
    }
    return ExitCodes.OK;
  }
}
