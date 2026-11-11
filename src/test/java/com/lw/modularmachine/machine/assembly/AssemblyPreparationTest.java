package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyPreparationTest {
  @AfterEach
  void cleanup() {
    AssemblyPreparation.clearCache();
    AsyncStructureWork.stop();
  }

  @Test
  void backgroundStagesReachPatternForEveryOrientationAndCachedReentry() throws Exception {
    var id = ResourceLocation.parse("mmcece:assembly_pipeline");
    // Empty rules keep this geometry/cache test independent of game registries.
    var rule = new MachineDefinition.Rule(null, List.of());
    var parts = List.of(new MachineDefinition.Part("a", new BlockPos(1, 2, 3), rule, null, PropertySpace.LOCAL));
    var compilation = OrientationCompilation.compile(id, OrientationPolicy.ALL_24, parts);
    var definition = new MachineDefinition(id, "test", parts, 0, OrientationPolicy.ALL_24, compilation);
    for (var orientation : MachineOrientation.all()) {
      var origin = new BlockPos(100, 70, -100);
      var pattern = await(
          new AssemblyPreparation(definition, 1, origin, orientation, definition.defaultDynamicLengths(-1)));
      assertEquals(1, pattern.parts().size());
      assertEquals(
          origin.offset(orientation.toWorldOffset(parts.getFirst().offset())),
          pattern.parts().getFirst().position());
      assertEquals(orientation, pattern.orientation());
      var cached = await(
          new AssemblyPreparation(definition, 1, origin.above(), orientation, definition.defaultDynamicLengths(-1)));
      assertEquals(pattern.parts().getFirst().position().above(), cached.parts().getFirst().position());
      assertSame(pattern.parts().getFirst().rule(), cached.parts().getFirst().rule());
    }
  }

  private static AssemblyPattern await(AssemblyPreparation task) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    AssemblyPattern pattern;
    while ((pattern = task.advance(System.nanoTime() + 2_000_000L)) == null) {
      assertTrue(System.nanoTime() < deadline, "Assembly preparation never completed");
      Thread.sleep(1);
    }

    return pattern;
  }
}
