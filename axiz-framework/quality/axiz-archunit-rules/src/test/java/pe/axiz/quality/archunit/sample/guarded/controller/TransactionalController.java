package pe.axiz.quality.archunit.sample.guarded.controller;

import org.springframework.transaction.annotation.Transactional;

public class TransactionalController {
  @Transactional
  public void change() {}
}
