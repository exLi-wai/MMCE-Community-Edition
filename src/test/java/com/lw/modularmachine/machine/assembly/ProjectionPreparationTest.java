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

class ProjectionPreparationTest {
  @AfterEach
  void cleanup() {
    ProjectionPreparation.clearCache();
    AsyncStructureWork.stop();
  }

  @Test
  void backgroundStagesReachReadyForEveryOrientationAndCachedReentry() throws Exception {
    var id = ResourceLocation.parse("mmcece:projection_pipeline");
    var rule = new MachineDefinition.Rule(null, List.of());
    var parts = List.of(new MachineDefinition.Part("a", new BlockPos(1, 2, 3), rule, null, PropertySpace.LOCAL));
    var compilation = OrientationCompilation.compile(id, OrientationPolicy.ALL_24, parts);
    var definition = new MachineDefinition(id, "test", parts, 0, OrientationPolicy.ALL_24, compilation);
    for (var orientation : MachineOrientation.all()) {
      var origin = new BlockPos(100, 70, -100);
      var task = new ProjectionPreparation(definition, 1, origin, orientation);
      var ready = await(task);
      assertEquals(1, ready.entries().size());
      assertEquals(
          origin.offset(orientation.toWorldOffset(parts.getFirst().offset())),
          ready.entries().getFirst().position());
      assertEquals(orientation, ready.pattern().orientation());
      var cached = await(new ProjectionPreparation(definition, 1, origin.above(), orientation));
      assertEquals(ready.entries().getFirst().position().above(), cached.entries().getFirst().position());
    }
  }

  private static ProjectionPreparation.Ready await(ProjectionPreparation task) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    ProjectionPreparation.Ready ready;
    while ((ready = task.advance(System.nanoTime() + 2_000_000L)) == null) {
      assertTrue(System.nanoTime() < deadline, "Projection preparation never completed");
      Thread.sleep(1);
    }
    return ready;
  }
}
