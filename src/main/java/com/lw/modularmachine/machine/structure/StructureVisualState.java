package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.appearance.MachineComponentVisibility;
import com.lw.modularmachine.block.StatedMachineComponentBlock;
import java.util.BitSet;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Budgeted color/visibility publication owned by one structure registration
 * generation.
 */
@ApiStatus.Internal
final class StructureVisualState {
  private final StructureService.Registration registration;
  private final CompiledStructure pattern;
  private int colorCursor;
  private boolean colorsComplete;
  private int visibilityCursor;
  private boolean visibilityComplete;
  private MachineComponentVisibility.Session visibility;
  private final BitSet statedParts = new BitSet();
  private int workingCursor;
  private boolean working;

  StructureVisualState(StructureService.Registration registration) {
    this.registration = registration;
    pattern = registration.pattern;
  }

  boolean colorsComplete() {
    return colorsComplete;
  }

  boolean visibilityComplete() {
    return visibilityComplete;
  }

  boolean hasVisibility() {
    return visibility != null;
  }

  boolean workingComplete() {
    return statedParts.nextSetBit(workingCursor) < 0;
  }

  void working(boolean value) {
    if (working != value) {
      working = value;
      workingCursor = 0;
    }
  }

  int advanceWorking(ServerLevel level, int budget) {
    int used = 0;
    int part;
    while (used < budget && (part = statedParts.nextSetBit(workingCursor)) >= 0) {
      workingCursor = part + 1;
      var position = pattern.position(part);
      used++;
      if (!level.hasChunkAt(position)) {
        continue;
      }
      var state = level.getBlockState(position);
      if (state.getBlock() instanceof StatedMachineComponentBlock) {
        var next = state.setValue(
            StatedMachineComponentBlock.WORKING_STATE,
            working ? StatedMachineComponentBlock.WorkingState.WORKING : StatedMachineComponentBlock.WorkingState.IDLE);
        if (next != state) {
          StructureChangeRouter.publishWorkingState(level, registration, part, state, next);
        }
      }
    }
    return used;
  }

  void setVisibilityActive(boolean active) {
    visibility.active(active);
  }

  void resetColors() {
    colorCursor = 0;
    colorsComplete = false;
    // Keep discovered positions until this generation retires: a rescan must
    // not forget components whose working appearance still needs clearing.
    workingCursor = 0;
    resetVisibility();
  }

  void suspendVisibility() {
    if (visibility != null) {
      visibility.active(false);
    }
  }

  void resetVisibility() {
    if (visibility != null) {
      visibility.close();
      visibility = null;
    }
    visibilityCursor = 0;
    visibilityComplete = false;
  }

  int advanceVisibility(ServerLevel level, int budget) {
    var definition = pattern.definition;
    if (!definition.hideComponentsWhenFormed()
        || definition.hiddenComponentTypes().stream().noneMatch(type -> type != MachineComponentType.CONTROLLER)) {
      visibilityComplete = true;
      return 1;
    }
    if (visibility == null) {
      visibility = MachineComponentVisibility.create(level, pattern.origin);
    }
    visibility.active(true);
    int used = 0;
    while (used < budget && visibilityCursor < definition.parts().size()) {
      var position = pattern.position(visibilityCursor++);
      var type = MachineComponentType.forBlock(level.getBlockState(position).getBlock());
      if (type != MachineComponentType.CONTROLLER && definition.hidesComponent(type)) {
        visibility.add(position);
      }
      used++;
    }
    visibility.flush();
    visibilityComplete = visibilityCursor == definition.parts().size();

    return used;
  }

  int advanceColors(ServerLevel level, int budget) {
    int used = 0;
    while (used < budget && colorCursor < pattern.definition.parts().size()) {
      int index = colorCursor++;
      var position = pattern.position(index);
      if (level.getBlockState(position).getBlock() instanceof StatedMachineComponentBlock) {
        if (!statedParts.get(index)) {
          statedParts.set(index);
          workingCursor = Math.min(workingCursor, index);
        }
      }
      if (level.getBlockEntity(position) instanceof MachineColorable colorable
          && (!colorable.machineColorAssigned() || colorable.machineColor() != pattern.definition.color())) {
        colorable.setMachineColor(pattern.definition.color());
      }
      used++;
    }
    colorsComplete = colorCursor == pattern.definition.parts().size();
    return used;
  }

}
