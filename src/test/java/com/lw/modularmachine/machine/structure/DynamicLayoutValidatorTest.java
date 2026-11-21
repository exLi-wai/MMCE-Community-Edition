package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLayoutValidatorTest {
  @Test
  void fixedIdConflictsWithAnExpandedPartOnlyAtPermittedLengths() {
    var root = machine();
    root.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("part_id", "dynamic:tower:body:1:offset:0,0,0");
    var segment = root.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject();
    segment.addProperty("maximum", 1);
    DynamicLayoutValidator.validate(root);
    var first = parse(root);
    DynamicLayoutValidator.validate(first);
    first.resolveDynamic(Map.of("tower", 1));

    segment.addProperty("maximum", 2);
    assertConflict(root, 2);
  }

  @Test
  void shortenedTemplateIdCannotCollideWithAnExplicitUuidInTheSameRepetition() {
    var root = machine();
    var body = root.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject().getAsJsonArray("parts");
    var longPart = body.get(0).getAsJsonObject();
    String localId = "x".repeat(120);
    longPart.addProperty("part_id", localId);
    var explicit = JsonParser.parseString("""
        {"offset":[0,1,0],"block":"item_input"}
        """).getAsJsonObject();
    explicit.addProperty("part_id", UUID.nameUUIDFromBytes(localId.getBytes(StandardCharsets.UTF_8)).toString());
    body.add(explicit);

    assertConflict(root, 1);
  }

  private static void assertConflict(JsonObject root, int length) {
    var definition = parse(root);
    assertThrows(IllegalArgumentException.class, () -> definition.resolveDynamic(Map.of("tower", length)));
    assertThrows(IllegalArgumentException.class, () -> DynamicLayoutValidator.validate(root));
    assertThrows(IllegalArgumentException.class, () -> DynamicLayoutValidator.validate(definition));
  }

  private static JsonObject machine() {
    return JsonParser.parseString("""
        {"parts":[{"offset":[1,0,0],"block":"item_input"}],
         "dynamic_patterns":[{"id":"tower","minimum":1,"maximum":2,"start_offset":[3,0,0],"step":[1,0,0],
           "parts":[{"offset":[0,0,0],"block":"item_input"}]}]}
        """).getAsJsonObject();
  }

  private static MachineDefinition parse(JsonObject root) {
    return MachineDefinitionParser.parse(
        ResourceLocation.parse("mmcece:layout_ids"),
        root,
        new StateOrientationAdapters.RegistrySnapshot(Map.of()));
  }
}
