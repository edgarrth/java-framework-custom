package pe.axiz.quality.archunit.sample.bff.controller;

import pe.axiz.quality.archunit.sample.bff.client.Downstream;
import pe.axiz.quality.archunit.sample.bff.composition.View;
import pe.axiz.quality.archunit.sample.bff.composition.nested.NestedView;
import pe.axiz.quality.archunit.sample.bff.config.RuntimeSettings;

/** Imported bytecode fixture; no runtime endpoint. */
public class Routes {
  public record Good(View view, NestedView nested, Downstream downstream, Routes transport) {}

  public record Bad(RuntimeSettings leakedConfiguration) {}
}
