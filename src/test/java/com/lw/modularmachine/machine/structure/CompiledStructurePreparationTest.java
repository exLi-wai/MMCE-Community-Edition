package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompiledStructurePreparationTest {
  private static final int[][] POINTS = {{2, 3, 5}, {-3, 2, 5}, {-2, -3, 5}, {3, -2, 5}, {-5, 3, 2}, {-5, 2, -3},
      {-5, -3, -2}, {-5, -2, 3}, {-2, 3, -5}, {3, 2, -5}, {2, -3, -5}, {-3, -2, -5}, {5, 3, -2}, {5, 2, 3}, {5, -3, 2},
      {5, -2, -3}, {2, -5, 3}, {-3, -5, 2}, {-2, -5, -3}, {3, -5, -2}, {2, 5, -3}, {-3, 5, -2}, {-2, 5, 3}, {3, 5, 2}};

  private static MachineDefinition definition(boolean legacy) {
    JsonObject root = JsonParser.parseString("""
        {"schema_version":2,"orientation":{"policy":"all_24"},"parts":[
        {"part_id":"marker","offset":[2,3,5],"port":"item_input"},
        {"part_id":"source","offset":[1,0,1],"port":"energy_input"},
        {"part_id":"target","offset":[-1,0,1],"port":"energy_output"}],
        "operation":{"type":"energy_transfer","energy_per_tick":128}}
        """).getAsJsonObject();
    if (legacy) {
      root.addProperty("schema_version", 1);
      root.remove("orientation");
      for (var part : root.getAsJsonArray("parts")) {
        part.getAsJsonObject().remove("part_id");
      }
    }
    return MachineDefinition.parse(
        ResourceLocation.fromNamespaceAndPath("mmcece", "preparation_unit"),
        root,
        new StateOrientationAdapters.RegistrySnapshot(Map.of()));
  }

  @Test
  void rulePreparationCachesOnlyACompleteImmutableResult() {
    var definition = definition(false);
    var preparation = definition.orientationCompilation().prepareRules(MachineOrientation.of(Direction.UP, 2));
    assertEquals(0, preparation.advance(0));
    assertEquals(1, preparation.advance(1));
    assertFalse(preparation.complete());
    assertEquals(0, definition.orientationCompilation().cachedOrientationCount());
    assertThrows(IllegalStateException.class, preparation::result);
    while (!preparation.complete()) {
      assertTrue(preparation.advance(1) <= 1);
    }
    assertEquals(1, definition.orientationCompilation().cachedOrientationCount());
    assertSame(preparation.result(), definition.rulesFor(MachineOrientation.of(Direction.UP, 2)));
    assertThrows(UnsupportedOperationException.class, () -> preparation.result().clear());
  }

  @Test
  void oneUnitBudgetsCannotExposeAPartiallyPreparedLayout() {
    var definition = definition(false);
    var origin = new BlockPos.MutableBlockPos(10, 40, -10);
    var preparation = CompiledStructure.prepare(definition, origin, MachineOrientation.IDENTITY);
    origin.set(100, 100, 100);
    assertThrows(IllegalStateException.class, preparation::result);
    assertEquals(0, preparation.advance(0));
    assertEquals(0, preparation.preparedPositions());
    int iterations = 0;
    while (!preparation.complete()) {
      assertEquals(1, preparation.advance(1));
      assertTrue(++iterations < 30);
    }
    var pattern = preparation.result();
    assertEquals(new BlockPos(10, 40, -10), pattern.origin);
    assertEquals(new BlockPos(12, 43, -5), pattern.position(0));
    assertEquals(0, preparation.advance(100));
    assertThrows(IllegalArgumentException.class, () -> preparation.advance(-1));
  }

  @Test
  void everyIncrementalOrientationMatchesLiteralAsymmetricCoordinates() {
    var definition = definition(false);
    for (var orientation : MachineOrientation.all()) {
      var preparation = CompiledStructure.prepare(definition, BlockPos.ZERO, orientation);
      while (!preparation.complete()) {
        assertTrue(preparation.advance(1) <= 1);
      }
      int[] expected = POINTS[orientation.stableIndex()];
      assertEquals(
          new BlockPos(expected[0], expected[1], expected[2]),
          preparation.result().position(0),
          orientation.key());
      assertSame(orientation, preparation.result().orientation);
    }
  }

  @Test
  void dependencyGroupsCoverEachPartOnceAndAreDefensivelyCopied() {
    var pattern = new CompiledStructure(definition(false), new BlockPos(15, 40, 15), MachineOrientation.IDENTITY);
    var indices = new HashSet<Integer>();
    for (int[] group : pattern.groups()) {
      for (int index : group) {
        assertTrue(indices.add(index));
      }
    }
    assertEquals(Set.of(0, 1, 2), indices);
    var copy = pattern.groups();
    copy[0][0] = 999;
    assertNotEquals(999, pattern.groups()[0][0]);
    for (int i = 0; i < 3; i++) {
      assertTrue(pattern.groupFor(pattern.position(i)) >= 0);
    }
  }

  @Test
  void unrepresentableAbsoluteCoordinatesNeverPublishACompletedLayout() {
    var preparation = CompiledStructure
        .prepare(definition(false), new BlockPos(Integer.MAX_VALUE, 0, 0), MachineOrientation.IDENTITY);
    assertThrows(ArithmeticException.class, () -> {
      while (!preparation.complete()) {
        preparation.advance(1);
      }
    });
    assertFalse(preparation.complete());
    assertThrows(IllegalStateException.class, preparation::result);
  }

  @Test
  void legacyDefinitionsRejectVerticalAndRolledPreparation() {
    var definition = definition(true);
    assertThrows(
        IllegalArgumentException.class,
        () -> CompiledStructure.prepare(definition, BlockPos.ZERO, MachineOrientation.of(Direction.UP, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CompiledStructure.prepare(definition, BlockPos.ZERO, MachineOrientation.of(Direction.NORTH, 1)));
    assertEquals(new BlockPos(-5, 3, 2), new CompiledStructure(definition, BlockPos.ZERO, Direction.EAST).position(0));
  }
}
