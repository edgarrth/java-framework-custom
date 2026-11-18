package pe.axiz.quality.archunit.sample.guarded.handler;

import org.springframework.web.bind.annotation.RequestMapping;

public class BareRoute {
  @RequestMapping("/hidden")
  public String get() {
    return "data";
  }
}
