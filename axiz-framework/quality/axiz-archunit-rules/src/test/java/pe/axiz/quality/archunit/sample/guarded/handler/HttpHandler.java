package pe.axiz.quality.archunit.sample.guarded.handler;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HttpHandler {
  @GetMapping("/hidden")
  @ResponseBody
  public String get() {
    return "data";
  }
}
