package com.lw.modularmachine.machine.appearance;

import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal common controller appearance mapping and server-owned publication.
 */
@ApiStatus.Internal
public final class ControllerAppearance {
  public static final BooleanProperty FORMED = BooleanProperty.create("formed");
  public static final BooleanProperty HIDDEN = BooleanProperty.create("hidden");
  public static final EnumProperty<ControllerAppearanceState> APPEARANCE = EnumProperty
      .create("appearance", ControllerAppearanceState.class);

  private ControllerAppearance() {
  }

  public static ControllerAppearanceState ordinary(VerificationState verification, boolean hasRun, ItemRunEngine.Result result, boolean signalPaused, boolean transferred, ControllerAppearanceState idleAppearance) {
    if (verification != VerificationState.CONFIRMED) {
      return ControllerAppearanceState.UNFORMED;
    }
    if (result == ItemRunEngine.Result.UPGRADE_CONFLICT
        || result == ItemRunEngine.Result.UPGRADE_ERROR
        || hasRun && result == ItemRunEngine.Result.ERROR) {
      return ControllerAppearanceState.ERROR;
    }
    if (signalPaused) {
      return ControllerAppearanceState.PAUSED;
    }
    if (!hasRun) {
      return transferred ? ControllerAppearanceState.RUNNING : idleAppearance;
    }

    return switch (result) {
      case ADVANCED, WAITING_TICK -> ControllerAppearanceState.RUNNING;
      case ERROR, UPGRADE_CONFLICT, UPGRADE_ERROR -> ControllerAppearanceState.ERROR;
      default -> ControllerAppearanceState.PAUSED;
    };
  }

  public static ControllerAppearanceState factory(VerificationState verification, List<FactoryThreadState> threads, Map<String, FactoryRunSet> runs, boolean sourcesValid) {
    if (verification != VerificationState.CONFIRMED) {
      return ControllerAppearanceState.UNFORMED;
    }
    boolean enabled = false;
    boolean disabled = false;
    boolean paused = false;
    boolean error = !sourcesValid;
    for (var thread : threads) {
      if (!thread.enabled()) {
        disabled = true;
        continue;
      }
      enabled = true;
      var runSet = runs.get(thread.threadId());
      if (thread.state() == FactoryThreadState.State.RUNNING
          && thread.reason() == FactoryThreadState.Reason.NONE
          && runSet != null
          && !runSet.runs().isEmpty()) {
        return ControllerAppearanceState.RUNNING;
      }
      if (thread.state() == FactoryThreadState.State.INVALID || failure(thread.reason())) {
        error = true;
      } else if (thread.state() == FactoryThreadState.State.BLOCKED
          || waiting(thread.reason())
          || runSet != null && !runSet.runs().isEmpty()) {
        paused = true;
      }
    }
    if (error) {
      return ControllerAppearanceState.ERROR;
    }
    if (paused || !enabled && disabled) {
      return ControllerAppearanceState.PAUSED;
    }

    return ControllerAppearanceState.FORMED;
  }

  private static boolean failure(FactoryThreadState.Reason reason) {
    return switch (reason) {
      case LOCKED_RECIPE_MISSING, CONTROLLER_UNAVAILABLE, CHECK_ERROR, UPGRADE_CONFLICT, UPGRADE_ERROR -> true;
      default -> false;
    };
  }

  private static boolean waiting(FactoryThreadState.Reason reason) {
    return switch (reason) {
      case INPUT_MISSING, OUTPUT_BLOCKED, WAITING_ENERGY, WAITING_FLUID, STALE_RESOURCES, UPGRADE_COST_MISSING,
          WAITING_UPGRADE_COST, OUTPUT_PLANNING, UPGRADE_DATA_PENDING, THREAD_LIMIT ->
        true;
      default -> false;
    };
  }

  public static void sync(BlockEntity controller, boolean formed, boolean hidden, ControllerAppearanceState appearance) {
    if (!(controller.getLevel() instanceof ServerLevel level) || controller.isRemoved()) {
      return;
    }
    ServerThreadGuard.require(level, "Controller appearance updated");
    if (!level.hasChunkAt(controller.getBlockPos()) || level.getBlockEntity(controller.getBlockPos()) != controller) {
      return;
    }
    var state = level.getBlockState(controller.getBlockPos());
    if (!state.hasProperty(FORMED) || !state.hasProperty(HIDDEN) || !state.hasProperty(APPEARANCE)) {
      return;
    }
    var next = state.setValue(FORMED, formed).setValue(HIDDEN, hidden).setValue(APPEARANCE, appearance);
    if (next != state) {
      level.setBlock(controller.getBlockPos(), next, 2);
    }
  }
}
