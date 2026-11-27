package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChemicalRecipeStateTest {
  private static JsonObject declaration() {
    return JsonParser.parseString("""
        {
          "type": "mmcece:machine", "machine": "mmcece:test", "duration": 20,
          "inputs": [], "outputs": [], "parallelized": true, "failure_action": "still",
          "chemicals": {
            "inputs": [{"chemical": "mekanism:hydrogen", "amount": 9007199254740993, "selector": "feed"}],
            "outputs": [{"chemical": "mekanism:oxygen", "amount": 9007199254740995, "selector": "product"}],
            "per_tick": [{"chemical": "mekanism:hydrogen", "amount": 2147483648, "selector": "feed"}]
          }
        }
        """).getAsJsonObject();
  }

  @Test
  void ignoredChemicalRowsKeepIndependentLongAmountsWhileRequiredInputsStayExact() {
    var chemical = ResourceLocation.parse("mekanism:oxygen");
    var rows = List.of(
        new ChemicalRequirement(chemical, 1),
        new ChemicalRequirement(chemical, Long.MAX_VALUE, "", 1, true),
        new ChemicalRequirement(chemical, Long.MAX_VALUE, "", 1, true));
    var data = new ChemicalRecipeData(List.of(), rows, List.of(), List.of());
    var encoded = ChemicalRecipeData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
    assertEquals(data, ChemicalRecipeData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    assertThrows(ArithmeticException.class, () -> new ChemicalRecipeData(rows, List.of(), List.of(), List.of()));
    var required = List.of(new ChemicalRequirement(chemical, 1), new ChemicalRequirement(chemical, Long.MAX_VALUE));
    assertThrows(ArithmeticException.class, () -> new ChemicalRecipeData(List.of(), required, List.of(), List.of()));
  }

  @Test
  void rejectsFractionalUnknownAndOverflowingChemicalDeclarations() {
    for (String amount : new String[]{"1.5", "0", "-1", "9223372036854775808", "\"1000\""}) {
      var source = declaration();
      source.getAsJsonObject("chemicals").getAsJsonArray("inputs").get(0).getAsJsonObject()
          .add("amount", JsonParser.parseString(amount));
      assertThrows(JsonParseException.class, () -> MachineRecipeJson.validate(source));
    }
    var source = declaration();
    var rows = source.getAsJsonObject("chemicals").getAsJsonArray("inputs");
    rows.get(0).getAsJsonObject().addProperty("amount", Long.MAX_VALUE);
    rows.add(rows.get(0).deepCopy());
    assertThrows(JsonParseException.class, () -> MachineRecipeJson.validate(source));

    var unknown = declaration();
    unknown.getAsJsonObject("chemicals").addProperty("ignore_output", true);
    assertThrows(JsonParseException.class, () -> MachineRecipeJson.validate(unknown));
    var empty = declaration();
    empty.remove("chemicals");
    assertThrows(JsonParseException.class, () -> MachineRecipeJson.validate(empty));
  }

  @Test
  void paidChemicalRunSurvivesFailureProgressAndPendingRecoveryWithoutItemPorts() {
    var source = FrozenRecipeRunTest.valid();
    source.put("definition", JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, declaration()));
    source.putInt("input_group", 7);
    var ports = new ListTag();
    ports.add(port("feed", "CHEMICAL_INPUT", "feed", 7));
    ports.add(port("product", "CHEMICAL_OUTPUT", "product", InputGroup.SHARED));
    source.put("ports", ports);

    var run = FrozenRecipeRun.read(source);
    source.getCompound("definition").getCompound("chemicals").getList("per_tick", Tag.TAG_COMPOUND).getCompound(0)
        .putLong("amount", 1);
    assertEquals(2147483648L, run.chemicalPerTick().getFirst().amount());
    assertThrows(UnsupportedOperationException.class, () -> run.chemicalPerTick().clear());
    var progressed = run.advanced(1, 12).afterResourceFailure(2, RecipeFailureAction.DECREASE);
    var restored = FrozenRecipeRun.read(progressed.save());
    assertEquals(11, restored.progress());
    assertEquals(run.runId(), restored.runId());
    assertEquals(7, restored.inputGroup());
    assertEquals(run.chemicalPerTick(), restored.chemicalPerTick());

    var pending = restored.advanced(3, 9).finished();
    var recovered = FrozenRecipeRun.read(pending.save());
    assertEquals(pending.save(), recovered.save());
    assertEquals(9007199254740995L, recovered.chemicalOutputs().getFirst().amount());
    assertEquals("product", recovered.chemicalOutputs().getFirst().selector());
    assertTrue(recovered.definition().getList("outputs", Tag.TAG_COMPOUND).isEmpty());

    var changedGroup = recovered.save();
    var recoveredPorts = changedGroup.getList("ports", Tag.TAG_COMPOUND);
    recoveredPorts.add(port("other", "CHEMICAL_INPUT", "other", 7));
    recoveredPorts.getCompound(0).putInt("input_group", 8);
    assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(changedGroup));
    recoveredPorts.getCompound(0).putInt("input_group", InputGroup.SHARED);
    assertEquals(7, FrozenRecipeRun.read(changedGroup).inputGroup());
  }

  private static CompoundTag port(String id, String kind, String selector, int group) {
    var port = new CompoundTag();
    port.putString("id", id);
    port.putString("kind", kind);
    port.putString("selector", selector);
    port.putInt("input_group", group);

    return port;
  }
}
