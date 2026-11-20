package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.api.controller.VerificationState;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Context;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.ResourceFailure;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.RunSnapshot;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import com.lw.modularmachine.api.recipe.RecipeRunState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeLifecycleContextTest {
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:test_machine");
  private static final ResourceLocation RECIPE = ResourceLocation.parse("mmcece:test_recipe");
  private static final UUID RUN = new UUID(0, 1);

  @Test
  void successfulTickAdvancesAndShortageCanOnlyHoldOrReduceProgress() {
    var before = List.of(run(1, 4, RecipeRunState.RUNNING));
    var finished = List.of(run(2, 5, RecipeRunState.OUTPUT_PENDING));
    var held = List.of(run(1, 5, RecipeRunState.RUNNING));
    var reset = List.of(run(0, 5, RecipeRunState.RUNNING));
    event(Type.TICK, before, finished);
    event(Type.RESOURCE_FAILURE, before, held);
    event(Type.RESOURCE_FAILURE, before, reset);
    assertThrows(IllegalArgumentException.class, () -> event(Type.TICK, before, held));
    assertThrows(IllegalArgumentException.class, () -> event(Type.RESOURCE_FAILURE, before, finished));
    assertThrows(IllegalArgumentException.class, () -> event(Type.TICK, before, List.of()));
  }

  @Test
  void startFinishAndDiscardAcceptOnlyTheirOwnRunState() {
    var running = run(0, 4, RecipeRunState.RUNNING);
    var pending = run(2, 4, RecipeRunState.OUTPUT_PENDING);
    event(Type.MACHINE_TICK, List.of(), List.of());
    event(Type.STARTED, List.of(), List.of(running));
    event(Type.FINISHED, List.of(pending), List.of());
    event(Type.DISCARDED, List.of(running), List.of());
    assertThrows(IllegalArgumentException.class, () -> event(Type.FINISHED, List.of(running), List.of()));
    assertThrows(IllegalArgumentException.class, () -> event(Type.DISCARDED, List.of(pending), List.of()));
    assertThrows(IllegalArgumentException.class, () -> event(Type.STARTED, List.of(), List.of(running, running)));
  }

  private static RunSnapshot run(int progress, long lastTick, RecipeRunState state) {
    return new RunSnapshot(RUN, RECIPE, state, 1, progress, 2, lastTick);
  }

  private static Context event(Type type, List<RunSnapshot> before, List<RunSnapshot> after) {
    var shortage = type == Type.RESOURCE_FAILURE || type == Type.DISCARDED ? Optional
        .of(ResourceFailure.ENERGY) : Optional.<ResourceFailure>empty();
    return new Context(
        type,
        ResourceLocation.parse("minecraft:overworld"),
        BlockPos.ZERO,
        MACHINE,
        false,
        Optional.empty(),
        5,
        VerificationState.CONFIRMED,
        VerificationFailure.NONE,
        before,
        after,
        shortage,
        new ControllerOwnership(true, Optional.empty(), Optional.empty(), false));
  }
}
