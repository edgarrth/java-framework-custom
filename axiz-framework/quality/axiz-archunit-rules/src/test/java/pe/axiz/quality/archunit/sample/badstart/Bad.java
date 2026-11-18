package pe.axiz.quality.archunit.sample.badstart;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Bad {
  private final String hidden = "logic";

  public static void main(String[] args) {}

  public String extra() {
    return hidden;
  }
}
