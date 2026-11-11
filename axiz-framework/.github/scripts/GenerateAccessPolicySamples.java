import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.render.JavaFormatter;

/** Real generated consumers exercise scoped, authenticated-only and public HTTP decisions. */
class GenerateAccessPolicySamples {
  private static final String CONTRACT =
      """
      openapi: 3.1.0
      info: {title: Access policy, version: 1.0.0, x-axiz-archetype: business}
      paths:
        /v1/access/scoped:
          get:
            operationId: readScoped
            tags: [access]
            security: [{oauth: [orders:read]}]
            responses: {'204': {description: Done}}
        /v1/access/authenticated:
          get:
            operationId: readAuthenticated
            tags: [access]
            security: [{jwt: []}]
            responses: {'204': {description: Done}}
        /v1/access/public:
          get:
            operationId: readPublic
            tags: [access]
            security: []
            responses: {'204': {description: Done}}
      components:
        securitySchemes:
          oauth:
            type: oauth2
            flows:
              clientCredentials:
                tokenUrl: https://issuer.test/token
                scopes: {orders:read: Read}
          jwt: {type: http, scheme: bearer}
      """;

  public static void main(String[] args) throws Exception {
    for (Stack stack : Stack.values()) {
      String name = "access-" + stack.id();
      String basePackage = "pe.axiz.samples.access." + stack.id();
      Path directory = Path.of(args[0]).resolve(name);
      var request =
          new ProjectRequest(
              name,
              "pe.axiz.samples",
              basePackage,
              "HTTP access policy acceptance",
              "business",
              Structure.HEXAGONAL,
              stack,
              "aurora",
              List.of("security"),
              CONTRACT,
              null,
              args[1]);
      ProjectGenerator.withDefaults().generate(request).writeTo(directory, false);
      Path test =
          directory.resolve(
              "src/test/java/"
                  + basePackage.replace('.', '/')
                  + "/adapter/in/rest/AccessAuthorizationTest.java");
      Files.writeString(
          test, JavaFormatter.format(testSource(basePackage, stack == Stack.REACTIVE)));
    }
  }

  private static String testSource(String basePackage, boolean reactive) {
    String imports =
        reactive
            ? """
            import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
            import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;
            import static org.mockito.Mockito.when;
            import org.junit.jupiter.api.BeforeEach;
            import org.springframework.context.ApplicationContext;
            import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
            import org.springframework.test.web.reactive.server.WebTestClient;
            import org.junit.jupiter.api.extension.ExtendWith;
            import pe.axiz.spring.test.BlockHoundExtension;
            import reactor.core.publisher.Mono;
            """
            : """
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
            import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
            import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
            import org.springframework.test.web.servlet.MockMvc;
            """;
    String setup =
        reactive
            ? """
            @Autowired ApplicationContext context;
            WebTestClient client;
            @BeforeEach void prepare() {
              client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
              when(scoped.execute(any())).thenReturn(Mono.empty());
              when(authenticated.execute(any())).thenReturn(Mono.empty());
              when(publicAccess.execute(any())).thenReturn(Mono.empty());
            }
            """
            : "@Autowired MockMvc client;\n";
    StringBuilder tests = new StringBuilder();
    for (String policy : List.of("scoped", "authenticated")) {
      String call =
          reactive
              ? "client.get().uri(\"/v1/access/"
                  + policy
                  + "\").exchange().expectStatus().isUnauthorized();"
              : "client.perform(get(\"/v1/access/"
                  + policy
                  + "\")).andExpect(status().isUnauthorized());";
      tests
          .append("@Test void ")
          .append(policy)
          .append("RejectsAnonymous() throws Exception {\n")
          .append(call)
          .append("\nverifyNoInteractions(scoped, authenticated, publicAccess);\n}\n");
    }
    String wrongScope =
        reactive
            ? "client.mutateWith(mockJwt().authorities(new"
                  + " SimpleGrantedAuthority(\"SCOPE_other\"))).get().uri(\"/v1/access/scoped\").exchange().expectStatus().isForbidden();"
            : "client.perform(get(\"/v1/access/scoped\").with(jwt().authorities(new"
                  + " SimpleGrantedAuthority(\"SCOPE_other\")))).andExpect(status().isForbidden());";
    tests
        .append("@Test void scopedRejectsWrongAuthority() throws Exception {\n")
        .append(wrongScope)
        .append("\nverifyNoInteractions(scoped, authenticated, publicAccess);\n}\n");
    for (String policy : List.of("scoped", "authenticated", "public")) {
      String credentials =
          policy.equals("public")
              ? ""
              : policy.equals("scoped") ? "new SimpleGrantedAuthority(\"SCOPE_orders:read\")" : "";
      String call =
          reactive
              ? "client"
                  + (policy.equals("public")
                      ? ""
                      : ".mutateWith(mockJwt().authorities(" + credentials + "))")
                  + ".get().uri(\"/v1/access/"
                  + policy
                  + "\").exchange().expectStatus().isNoContent();"
              : "client.perform(get(\"/v1/access/"
                  + policy
                  + "\")"
                  + (policy.equals("public") ? "" : ".with(jwt().authorities(" + credentials + "))")
                  + ").andExpect(status().isNoContent());";
      tests
          .append("@Test void ")
          .append(policy)
          .append("AllowsItsDeclaredCaller() throws Exception {\n")
          .append(call)
          .append("\nverify(")
          .append(policy.equals("public") ? "publicAccess" : policy)
          .append(").execute(any());\n}\n");
    }
    return "package "
        + basePackage
        + ".adapter.in.rest;\n"
        + imports
        + """
        import static org.mockito.ArgumentMatchers.any;
        import static org.mockito.Mockito.verify;
        import static org.mockito.Mockito.verifyNoInteractions;
        import org.junit.jupiter.api.Test;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
        import org.springframework.test.context.bean.override.mockito.MockitoBean;
        import org.springframework.security.core.authority.SimpleGrantedAuthority;
        import pe.axiz.spring.security.AxizSecurityAutoConfiguration;
        import pe.axiz.spring.web.AxizWebAutoConfiguration;
        """
        + "import "
        + basePackage
        + ".application.usecase.ReadScopedUseCase;\n"
        + "import "
        + basePackage
        + ".application.usecase.ReadAuthenticatedUseCase;\n"
        + "import "
        + basePackage
        + ".application.usecase.ReadPublicUseCase;\n"
        + (reactive ? "@WebFluxTest" : "@WebMvcTest")
        + """
        (controllers = AccessController.class, properties = {
          "axiz.security.issuers[0].issuer=https://issuer.test",
          "axiz.security.issuers[0].jwk-set-uri=https://issuer.test/jwks",
          "axiz.security.issuers[0].audiences[0]=test"
        })
        @ImportAutoConfiguration({AxizSecurityAutoConfiguration.class, AxizWebAutoConfiguration.class})
        """
        + (reactive ? "@ExtendWith(BlockHoundExtension.class)\n" : "")
        + "class AccessAuthorizationTest {\n"
        + setup
        + """
        @MockitoBean ReadScopedUseCase scoped;
        @MockitoBean ReadAuthenticatedUseCase authenticated;
        @MockitoBean ReadPublicUseCase publicAccess;
        """
        + tests
        + "}\n";
  }
}
