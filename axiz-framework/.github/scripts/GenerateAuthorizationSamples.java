import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

/** Generates real services whose authorization tests must reject an incomplete set of scopes. */
class GenerateAuthorizationSamples {
  public static void main(String[] args) throws Exception {
    String contract = Files.readString(Path.of(args[1]))
        .replace("paths:\n", "security:\n  - oauth: [orders:read, orders:write]\npaths:\n")
        .replace("components:\n", """
            components:
              securitySchemes:
                oauth:
                  type: oauth2
                  flows:
                    clientCredentials:
                      tokenUrl: https://issuer.test/token
                      scopes: {orders:read: Read, orders:write: Write}
            """);
    for (Stack stack : List.of(Stack.SERVLET, Stack.REACTIVE)) {
      String suffix = stack == Stack.SERVLET ? "servlet" : "reactive";
      var request = new ProjectRequest(
          "authorization-" + suffix, "pe.axiz.samples", "pe.axiz.samples." + suffix,
          "Generated authorization acceptance", "business", Structure.HEXAGONAL,
          stack, "aurora", List.of("security"), contract, null, args[2]);
      ProjectGenerator.withDefaults().generate(request)
          .writeTo(Path.of(args[0]).resolve(suffix), false);
    }
  }
}
