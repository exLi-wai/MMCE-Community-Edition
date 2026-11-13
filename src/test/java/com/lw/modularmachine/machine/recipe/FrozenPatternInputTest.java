package com.lw.modularmachine.machine.recipe;

import static org.junit.jupiter.api.Assertions.*;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.network.pattern.PatternContainerRef;
import com.lw.modularmachine.machine.network.pattern.PatternProviderReference;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FrozenPatternInputTest {
  private static PatternRunSources.Binding binding(String selector) {
    var provider = new PatternProviderReference(
        ResourceLocation.parse("minecraft:overworld"),
        BlockPos.ZERO,
        new UUID(3, 4));
    return new PatternRunSources.Binding(
        provider,
        new PatternContainerRef(provider.provider(), 0, 1),
        new UUID(5, 6),
        selector);
  }

  private static CompoundTag patternRun(String selector, String... roles) {
    var data = FrozenItemRunTest.valid();
    // This machine has a physical output bus but no ordinary input bus.
    data.getList("ports", Tag.TAG_COMPOUND).removeFirst();
    var pattern = binding(selector).save();
    var kinds = new ListTag();
    for (String role : roles) {
      kinds.add(StringTag.valueOf(role));
    }
    pattern.put("kinds", kinds);
    data.put("pattern_inputs", pattern);
    return data;
  }

  @Test
  void itemPatternInputSurvivesFreezeProgressAndRestoreWithoutAddingStructuralPorts() {
    var run = FrozenItemRun.read(patternRun("", "ITEM_INPUT", "FLUID_INPUT"));
    assertEquals(Map.of("out", PortKind.ITEM_OUTPUT), run.logicalPorts());
    assertTrue(run.matchesPatternSource(binding("")));
    var restored = FrozenItemRun.read(run.advanced(1).save());
    assertTrue(run.sameRecipe(restored));
    assertEquals(run.logicalPorts(), restored.logicalPorts());
    assertTrue(restored.matchesPatternSource(binding("")));
    assertTrue(FrozenItemRun.read(restored.advanced(2, 20).finished().save()).matchesPatternSource(binding("")));
  }

  @Test
  void missingOrReboundSourceCannotTurnPatternRunIntoOrdinaryRun() {
    var run = FrozenItemRun.read(patternRun("feed", "ITEM_INPUT"));
    assertFalse(run.matchesPatternSource(null));
    assertFalse(run.matchesPatternSource(binding("other")));
    var original = binding("feed");
    var replacement = new PatternRunSources.Binding(
        original.provider(),
        new PatternContainerRef(original.provider().provider(), 0, 2),
        original.job(),
        original.selector());
    assertFalse(run.matchesPatternSource(replacement));
    assertFalse(FrozenItemRun.read(FrozenItemRunTest.valid()).matchesPatternSource(original));
  }

  @Test
  void chemicalPatternRoleValidatesItsSelectorWithoutAnOrdinaryChemicalPort() {
    var data = patternRun("feed", "ITEM_INPUT", "CHEMICAL_INPUT");
    var fee = new CompoundTag();
    fee.putString("chemical", "mekanism:hydrogen");
    fee.putLong("amount", 100);
    fee.putString("selector", "feed");
    var rows = new ListTag();
    rows.add(fee);
    var chemicals = new CompoundTag();
    chemicals.put("per_tick", rows);
    data.getCompound("definition").put("chemicals", chemicals);
    var restored = FrozenItemRun.read(FrozenItemRun.read(data).save());
    assertEquals("feed", restored.chemicalPerTick().getFirst().selector());
    fee.putString("selector", "other");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(data));
  }

  @Test
  void patternInputsNeverSatisfyOutputRolesOrPermitDuplicateKinds() {
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(patternRun("", "ITEM_OUTPUT")));
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(patternRun("", "ITEM_INPUT", "ITEM_INPUT")));
    var data = patternRun("", "ITEM_INPUT");
    data.put("ports", new ListTag());
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(data));
    var missing = patternRun("", "ITEM_INPUT");
    missing.getCompound("pattern_inputs").remove("provider");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(missing));
  }
}
