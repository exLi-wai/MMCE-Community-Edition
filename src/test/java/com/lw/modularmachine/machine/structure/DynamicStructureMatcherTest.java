package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicStructureMatcherTest {
  private static final BlockPos ORIGIN = new BlockPos(10, 40, 10);

  @Test
  void maximumLengthAcceptsATailThatAlsoMatchesTheBody() {
    for (int maximum : new int[]{0, 1, 3}) {
      var definition = definition(maximum, true);
      for (int budget : new int[]{1, 64}) {
        var matcher = match(definition, expanded(definition, maximum), budget, null);
        assertTrue(matcher.matched(), "maximum=" + maximum + ", budget=" + budget);
        assertEquals(Map.of("line", maximum), matcher.lengths());
      }
    }
  }

  @Test
  void anExtraBodyBeforeTheTailStillExceedsTheMaximum() {
    var matcher = match(definition(1, true), expanded(definition(2, true), 2), 1, null);
    assertFalse(matcher.matched());
    assertEquals(VerificationFailure.MISMATCH, matcher.failure().reason());
    // The side marker at the legal tail position is absent: do not skip ahead.
    assertEquals(ORIGIN.offset(1, 0, 2), matcher.failure().position());
  }

  @Test
  void maximumWithoutATailStillRejectsAnExtraBody() {
    var definition = definition(1, false);
    assertEquals(Map.of("line", 1), match(definition, expanded(definition, 1), 1, null).lengths());
    var overlong = match(definition, expanded(definition(2, false), 2), 1, null);
    assertFalse(overlong.matched());
    assertEquals(VerificationFailure.MISMATCH, overlong.failure().reason());
  }

  @Test
  void unavailableTailAtMaximumCannotPublishLengths() {
    var definition = definition(1, true);
    var matcher = match(definition, expanded(definition, 1), 1, ORIGIN.offset(1, 0, 2));
    assertFalse(matcher.matched());
    assertEquals(VerificationFailure.CHUNK_UNAVAILABLE, matcher.failure().reason());
    assertThrows(IllegalStateException.class, matcher::lengths);
  }

  @Test
  void aShorterBodyLikeTailStillUsesTheExistingFallback() {
    var definition = definition(3, true);
    var matcher = match(definition, expanded(definition, 1), 1, null);
    assertTrue(matcher.matched());
    assertEquals(Map.of("line", 1), matcher.lengths());
  }

  private static DynamicStructureMatcher match(MachineDefinition definition, Map<BlockPos, PortKind> world, int budget, BlockPos unavailable) {
    var matcher = new DynamicStructureMatcher(definition, ORIGIN, MachineOrientation.IDENTITY);
    var watched = new HashSet<BlockPos>();
    int calls = 0;
    while (!matcher.complete()) {
      int used = matcher
          .advance(budget, (position, contents) -> watched.add(position), (position, part, rule, inverse) -> {
            assertTrue(watched.contains(position), "Every probe must be subscribed before reading");
            if (position.equals(unavailable)) {
              return CheckResult.UNAVAILABLE;
            }
            return world.get(position) == rule.port() ? CheckResult.MATCH : CheckResult.MISMATCH;
          });
      assertTrue(used > 0 && used <= budget);
      assertTrue(++calls < 100, "Length discovery must complete under one-unit slices");
    }
    return matcher;
  }

  private static Map<BlockPos, PortKind> expanded(MachineDefinition definition, int length) {
    var world = new HashMap<BlockPos, PortKind>();
    for (var part : definition.resolveDynamic(Map.of("line", length)).parts()) {
      assertNull(world.put(ORIGIN.offset(part.offset()), part.rule().port()), "Expanded parts cannot overlap");
    }
    return Map.copyOf(world);
  }

  private static MachineDefinition definition(int maximum, boolean tail) {
    var root = JsonParser.parseString("""
        {"parts":[{"offset":[2,0,0],"block":"energy_input"}],
        "dynamic_patterns":[{"id":"line","minimum":0,"maximum":3,
          "start_offset":[0,0,1],"step":[0,0,1],
          "parts":[{"offset":[0,0,0],"block":"item_input"}],
          "parts_end":[{"offset":[0,0,0],"block":"item_input"},
                       {"offset":[1,0,0],"block":"item_output"}]}]}
        """).getAsJsonObject();
    var segment = root.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject();
    segment.addProperty("maximum", maximum);
    if (!tail) {
      segment.remove("parts_end");
    }
    return MachineDefinition.parse(
        ResourceLocation.fromNamespaceAndPath("mmcece", "dynamic_matcher_test"),
        root,
        new StateOrientationAdapters.RegistrySnapshot(Map.of()));
  }
}
