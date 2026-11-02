package pe.axiz.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Structure of the declarative recipes (the recipes themselves run in the upgrade workflow, where
 * OpenRewrite is available): every document is a recipe, names are unique and every axiz recipe
 * referenced from a list exists.
 */
class RecipesTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> recipes() throws Exception {
    Map<String, Map<String, Object>> out = new HashMap<>();
    try (InputStream in = RecipesTest.class.getResourceAsStream("/META-INF/rewrite/axiz.yml")) {
      for (Object doc : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(in)) {
        Map<String, Object> recipe = (Map<String, Object>) doc;
        assertEquals("specs.openrewrite.org/v1beta/recipe", recipe.get("type"));
        String name = (String) recipe.get("name");
        assertTrue(out.put(name, recipe) == null, "duplicate recipe " + name);
        assertTrue(
            recipe.get("displayName") instanceof String
                && recipe.get("description") instanceof String,
            name);
      }
    }
    return out;
  }

  @Test
  void entryPointAndReferencesExist() throws Exception {
    Map<String, Map<String, Object>> recipes = recipes();
    assertTrue(
        recipes.containsKey("pe.axiz.UpgradeToAxiz"),
        "axiz upgrade activates pe.axiz.UpgradeToAxiz");
    for (Map<String, Object> recipe : recipes.values()) {
      for (Object step : (List<?>) recipe.get("recipeList")) {
        String name =
            step instanceof Map<?, ?> m
                ? String.valueOf(m.keySet().iterator().next())
                : String.valueOf(step);
        if (name.startsWith("pe.axiz.")) {
          if (!recipes.containsKey(name)) {
            assertTrue(
                org.openrewrite.Recipe.class.isAssignableFrom(Class.forName(name)),
                "unknown recipe " + name);
          }
        }
      }
    }
  }
}
