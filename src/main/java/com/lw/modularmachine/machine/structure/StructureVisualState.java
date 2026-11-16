package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.appearance.MachineComponentVisibility;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Budgeted color/visibility publication owned by one structure registration
 * generation.
 */
@ApiStatus.Internal
final class StructureVisualState {
  private final CompiledStructure pattern;
  private int colorCursor;
  private boolean colorsComplete;
  private int visibilityCursor;
  private boolean visibilityComplete;
  private MachineComponentVisibility.Session visibility;

  StructureVisualState(CompiledStructure pattern) {
    this.pattern = pattern;
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

  void setVisibilityActive(boolean active) {
    visibility.active(active);
  }

  void resetColors() {
    colorCursor = 0;
    colorsComplete = false;
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
      var position = pattern.position(colorCursor++);
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
