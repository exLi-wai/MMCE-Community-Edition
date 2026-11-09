package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters.RegistrySnapshot;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureChangeRoutingTest {
  private static CompiledStructure enclosingPattern(BlockPos origin) {
    var source = JsonParser.parseString("""
        {"parts":[{"offset":[-1,0,0],"block":"energy_input"},
                  {"offset":[1,0,0],"block":"energy_output"}]}
        """).getAsJsonObject();
    var definition = MachineDefinition
        .parse(ResourceLocation.parse("mmcece:origin_routing"), source, new RegistrySnapshot(Map.of()));

    return new CompiledStructure(definition, origin, MachineOrientation.IDENTITY);
  }

  @Test
  void ownerVisualChangeCannotDirtyItsEnclosingPartGroups() {
    var origin = new BlockPos(164, 69, 175);
    var pattern = enclosingPattern(origin);
    assertTrue(pattern.contains(origin), "The bounding box contains the undeclared controller anchor");
    assertFalse(StructureService.observesComponentChange(pattern, origin));
    assertTrue(StructureService.observesComponentChange(pattern, origin.west()));
    assertTrue(StructureService.observesComponentChange(pattern, origin.east()));
  }

  @Test
  void anotherStructureStillObservesThisControllerWhenItIsARealPart() {
    var controller = BlockPos.ZERO;
    var other = enclosingPattern(controller.east());
    assertEquals(controller, other.position(0));
    assertTrue(StructureService.observesComponentChange(other, controller));
    assertFalse(StructureService.observesComponentChange(other, other.origin));
  }

  @Test
  void ownOriginAsTheFirstUnmatchedDynamicBoundaryDoesNotRestartItsLengthProof() {
    var origin = BlockPos.ZERO;
    var source = JsonParser.parseString("""
        {"parts":[{"offset":[1,0,0],"block":"energy_output"}],
         "dynamic_patterns":[{"id":"line","minimum":1,"maximum":1,
           "start_offset":[-1,0,0],"step":[1,0,0],
           "parts":[{"offset":[0,0,0],"block":"energy_input"}]}]}
        """).getAsJsonObject();
    var definition = MachineDefinition
        .parse(ResourceLocation.parse("mmcece:origin_boundary"), source, new RegistrySnapshot(Map.of()));
    var matcher = new DynamicStructureMatcher(definition, origin, MachineOrientation.IDENTITY);
    var watches = new DynamicStructureWatches();
    matcher.advance(
        32,
        (segment, position, contents) -> watches.add(segment, position, contents),
        (position, part, rule, inverse) -> position.equals(origin.west()) ? CheckResult.MATCH : CheckResult.MISMATCH);
    assertTrue(matcher.matched());
    assertEquals(
        0L,
        watches.affected(origin, false),
        "The reserved owner anchor is not a repeated-component dependency");
    if (StructureService.observesDynamicChange(origin, origin)) {
      matcher.invalidate(watches.affected(origin, false));
    }
    assertTrue(matcher.matched(), "The owner's visual state must not invalidate its own boundary proof");
    assertTrue(StructureService.observesDynamicChange(origin.east(), origin));
  }
}
