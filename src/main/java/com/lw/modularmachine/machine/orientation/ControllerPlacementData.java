package com.lw.modularmachine.machine.orientation;

import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only interpretation of portable controller data. Unknown/corrupt data
 * must never become a default machine.
 */
public record ControllerPlacementData(ResourceLocation machineId, boolean opaque) {
  public static final ResourceLocation UNBOUND_MACHINE = ResourceLocation.fromNamespaceAndPath("mmcece", "unbound");
  public static final int CURRENT_SCHEMA = 2;
  public static final ResourceLocation DEFAULT_MACHINE = ResourceLocation.fromNamespaceAndPath("mmcece", "transformer");

  public static ControllerPlacementData fromStack(ItemStack stack) {
    var component = stack.get(DataComponents.BLOCK_ENTITY_DATA);
    return component == null ? new ControllerPlacementData(UNBOUND_MACHINE, false) : fromTag(component.copyTag());
  }

  public static ControllerPlacementData fromTag(CompoundTag root) {
    if (root.contains("mmcece") && !root.contains("mmcece", Tag.TAG_COMPOUND)) {
      return new ControllerPlacementData(null, true);
    }
    CompoundTag data = root.getCompound("mmcece");
    if (data.getInt("schema") == 2) {
      for (String field : data.getAllKeys()) {
        if (!Set.of("schema", "machine", "successful_transfers", "run").contains(field)) {
          return new ControllerPlacementData(null, true);
        }
      }
    }
    if (data.contains("schema")
        && (!data.contains("schema", Tag.TAG_INT)
            || data.getInt("schema") < 0
            || data.getInt("schema") > CURRENT_SCHEMA)) {
      return new ControllerPlacementData(null, true);
    }
    if (data.contains("run")) {
      if (data.getInt("schema") != 2
          || !data.contains("run", Tag.TAG_COMPOUND)
          || !data.contains("machine", Tag.TAG_STRING)) {
        return new ControllerPlacementData(null, true);
      }
      try {
        var run = FrozenItemRun.read(data.getCompound("run"));
        if (!run.machineId().toString().equals(data.getString("machine"))) {
          return new ControllerPlacementData(null, true);
        }
      } catch (RuntimeException invalid) {
        return new ControllerPlacementData(null, true);
      }
    }
    if (!data.contains("machine")) {
      return new ControllerPlacementData(UNBOUND_MACHINE, false);
    }
    if (!data.contains("machine", Tag.TAG_STRING)) {
      return new ControllerPlacementData(null, true);
    }
    String value = data.getString("machine");
    if (value.isBlank()) {
      return new ControllerPlacementData(null, true);
    }
    var id = ResourceLocation.tryParse(value);
    return id == null
        || id.getPath().isEmpty() ? new ControllerPlacementData(null, true) : new ControllerPlacementData(id, false);
  }
}
