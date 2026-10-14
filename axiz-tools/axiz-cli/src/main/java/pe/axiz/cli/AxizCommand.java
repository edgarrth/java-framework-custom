package pe.axiz.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code axiz}: the root command. */
@Command(
    name = "axiz",
    mixinStandardHelpOptions = true,
    versionProvider = Version.class,
    description =
        "axiz platform: create services from a contract and keep them coherent with the framework.",
    subcommands = {
      NewCommand.class,
      AddCommand.class,
      GenerateCommand.class,
      ContractCommand.class,
      UpgradeCommand.class,
      DoctorCommand.class,
      LocalCommand.class,
      CatalogCommand.class
    })
public class AxizCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }
}
