package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.api.upgrade.UpgradeDescriptions;
import dev.latvian.mods.rhino.ContextFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;

/**
 * Exercises function and array conversion through the actual Rhino Java bridge.
 */
public class KubeJSPreparationRhinoTest {
  public static final class Bridge {
    private RecipePreparationEvents.Handler check;
    private UpgradeDescriptions.Handler description;

    public void setCheck(RecipePreparationEvents.Handler value) {
      check = value;
    }

    public void setDescription(UpgradeDescriptions.Handler value) {
      description = value;
    }
  }

  @Test
  void javascriptCheckCanLowerParallelismButCannotEditARetainedEvent() {
    var context = new ContextFactory().enter();
    var scope = context.initStandardObjects();
    var bridge = new Bridge();
    context.addToScope(scope, "bridge", bridge);
    context.evaluateString(scope, """
        let retained
        bridge.setCheck(event => {
          retained = event
          if (event.getPhase() === 'START') event.setParallelism(2)
        })
        """, "preparation-function", 1, null);
    var event = new RecipePreparationEvents.Event(null, RecipePreparationEvents.Type.PRE_CHECK, 8);
    bridge.check.handle(event);
    assertEquals(2, event.getParallelism());
    event.seal();
    assertThrows(
        RuntimeException.class,
        () -> context.evaluateString(scope, "retained.setParallelism(1)", "retained-preparation", 1, null));
  }

  @Test
  void descriptionCallbackConvertsJavascriptArrayToStringList() {
    var context = new ContextFactory().enter();
    var scope = context.initStandardObjects();
    var bridge = new Bridge();
    context.addToScope(scope, "bridge", bridge);
    context.evaluateString(scope, "bridge.setDescription(context => ['001', '速度升级'])", "upgrade-description", 1, null);
    assertEquals(List.of("001", "速度升级"), UpgradeDescriptions.validate(bridge.description.describe(null)));
  }
}
