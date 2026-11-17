package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.controller.ControllerScriptData;
import dev.latvian.mods.kubejs.util.JsonUtils;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the same JSON type wrapper that KubeJS registers for script calls.
 */
class KubeJSMachineScriptDataRhinoTest {
  private record Script(Context context, Scriptable scope) {
  }

  private static Script script() {
    var factory = new ContextFactory();
    factory.getTypeWrappers().register(JsonObject.class, (context, value, type) -> JsonUtils.objectOf(context, value));
    var context = factory.enter();
    var scope = context.initStandardObjects();
    context.addToScope(scope, "event", new KubeJSMachineRegistration(false));

    return new Script(context, scope);
  }

  @Test
  void javascriptObjectIsCapturedBeforeTheCallerChangesIt() {
    var script = script();
    var result = script.context().evaluateString(script.scope(), """
        const data = {code: '001', nested: {active: true}, rows: [{value: '7'}], missing: null}
        const proposal = event.scriptData(data, {})
        data.nested.active = false
        data.rows[0].value = 'changed'
        proposal.customData()
        """, "machine-object-data", 1, null);
    var captured = JsonParser.parseString((String) script.context().jsToJava(result, TypeInfo.STRING))
        .getAsJsonObject();

    assertEquals("001", captured.get("code").getAsString());
    assertTrue(captured.get("code").getAsJsonPrimitive().isString());
    assertTrue(captured.getAsJsonObject("nested").get("active").getAsBoolean());
    assertEquals("7", captured.getAsJsonArray("rows").get(0).getAsJsonObject().get("value").getAsString());
    assertTrue(captured.get("missing").isJsonNull());
  }

  @Test
  void detachedDataSupportsNestedJavascriptEditsWithoutChangingTheSnapshotOrProposal() {
    var snapshot = new ControllerScriptData(
        0,
        "{\"code\":\"001\",\"count\":2,\"ratio\":0.25,\"nested\":{\"active\":false},\"rows\":[{\"value\":\"7\"}],\"remove\":true,\"missing\":null}",
        Map.of());
    var script = script();
    script.context().addToScope(script.scope(), "snapshot", snapshot);
    var result = script.context().evaluateString(script.scope(), """
        const data = event.customData(snapshot)
        data.count += 1
        data.ratio *= 2
        data.nested.active = true
        data.rows[0].value = 'updated'
        delete data.remove
        const proposal = event.scriptData(data, snapshot.customModifiers())
        data.nested.active = false
        data.rows[0].value = 'later'
        proposal.customData()
        """, "machine-detached-data", 1, null);
    var captured = JsonParser.parseString((String) script.context().jsToJava(result, TypeInfo.STRING))
        .getAsJsonObject();
    var original = JsonParser.parseString(snapshot.customData()).getAsJsonObject();

    assertTrue(captured.getAsJsonObject("nested").get("active").getAsBoolean());
    assertEquals("updated", captured.getAsJsonArray("rows").get(0).getAsJsonObject().get("value").getAsString());
    assertFalse(captured.has("remove"));
    assertEquals("001", captured.get("code").getAsString());
    assertTrue(captured.get("code").getAsJsonPrimitive().isString());
    assertTrue(captured.get("missing").isJsonNull());
    assertEquals(3, captured.get("count").getAsInt());
    assertEquals(0.5, captured.get("ratio").getAsDouble());
    assertFalse(original.getAsJsonObject("nested").get("active").getAsBoolean());
    assertEquals("7", original.getAsJsonArray("rows").get(0).getAsJsonObject().get("value").getAsString());
    assertTrue(original.has("remove"));
  }

  @Test
  void nonObjectRootsCannotBecomeControllerScriptData() {
    var script = script();
    for (var expression : new String[]{"[]", "42", "true", "null"}) {
      assertThrows(
          RuntimeException.class,
          () -> script.context().evaluateString(
              script.scope(),
              "event.scriptData(" + expression + ", {})",
              "machine-invalid-data",
              1,
              null));
    }
  }

  @Test
  void javascriptObjectsStillRespectTheControllerDataDepthLimit() {
    var script = script();
    assertThrows(RuntimeException.class, () -> script.context().evaluateString(script.scope(), """
        let data = {}
        for (let i = 0; i < 32; i++) data = {nested: data}
        event.scriptData(data, {})
        """, "machine-data-depth", 1, null));
  }
}
