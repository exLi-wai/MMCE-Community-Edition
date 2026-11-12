package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import java.util.List;
import java.util.concurrent.CompletionException;
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
  void rejectsPartsWithoutProjectionCandidates() {
    var id = ResourceLocation.parse("mmcece:projection_pipeline");
    var rule = new MachineDefinition.Rule(null, List.of());
    var parts = List.of(new MachineDefinition.Part("a", new BlockPos(1, 2, 3), rule, null, PropertySpace.LOCAL));
    var compilation = OrientationCompilation.compile(id, OrientationPolicy.ALL_24, parts);
    var definition = new MachineDefinition(id, "test", parts, 0, OrientationPolicy.ALL_24, compilation);
    var task = new ProjectionPreparation(definition, 1, BlockPos.ZERO, MachineOrientation.IDENTITY);
    var failure = assertThrows(CompletionException.class, () -> await(task));
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    assertEquals("Selected projection has an unrepresentable part", failure.getCause().getMessage());
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
