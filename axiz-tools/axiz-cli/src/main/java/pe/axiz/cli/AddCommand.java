package pe.axiz.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.project.CapabilityAdder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** {@code axiz add <capability>...}: starter, properties, ConfigMap keys, tables and an example. */
@Command(
    mixinStandardHelpOptions = true,
    name = "add",
    description = "Adds capabilities to the service (see axiz catalog capabilities).")
class AddCommand extends BaseCommand implements Callable<Integer> {

  @Parameters(arity = "1..*", paramLabel = "<capability>", description = "Capabilities to add.")
  List<String> capabilities = new ArrayList<>();

  @Override
  public Integer call() throws IOException {
    CapabilityAdder adder = new CapabilityAdder(Catalog.load());
    for (String capability : capabilities) {
      CapabilityAdder.Result r = adder.add(project(), capability);
      if (r.added().isEmpty()) {
        ok(capability + " is already in the project");
        continue;
      }
      ok("Added " + String.join(", ", r.added()));
      r.changed().forEach(f -> out().println("    " + f));
      if (!r.configMapKeys().isEmpty()) {
        warn(
            "Set these keys in the ConfigMap of every environment"
                + " (deploy/helm/*/values-<env>.yaml):");
        r.configMapKeys().forEach(k -> out().println("    " + k));
      }
      out().println("  Example:");
      r.example().lines().forEach(l -> out().println("    " + l));
    }
    return ExitCodes.OK;
  }
}
