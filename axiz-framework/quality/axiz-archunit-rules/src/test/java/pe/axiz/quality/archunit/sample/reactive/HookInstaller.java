package pe.axiz.quality.archunit.sample.reactive;

import reactor.core.publisher.Hooks;

/** Installs a global Reactor hook: what NO_REACTOR_GLOBAL_HOOKS reports. */
public class HookInstaller {

  public void install() {
    Hooks.onOperatorDebug();
  }
}
