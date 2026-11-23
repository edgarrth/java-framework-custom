package pe.axiz.quality.archunit.sample.reactive;

import reactor.core.publisher.Hooks;

/** Instala un hook global de Reactor: lo que informa NO_REACTOR_GLOBAL_HOOKS. */
public class HookInstaller {

  public void install() {
    Hooks.onOperatorDebug();
  }
}
