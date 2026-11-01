package com.lw.modularmachine.machine.factory;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Binding intent only; live membership and all physical resources remain
 * server-owned.
 */
public record UpgradeBusBinding(BlockPos controller, ResourceLocation machine) {
  public static final int MAX_BINDINGS = 64;

  public UpgradeBusBinding {
    controller = controller.immutable();
    if (machine == null || machine.getPath().isEmpty() || machine.toString().length() > 256) {
      throw new IllegalArgumentException("Invalid upgrade bus machine binding");
    }
  }

  public static List<UpgradeBusBinding> ordered(Collection<UpgradeBusBinding> bindings) {
    return bindings.stream().sorted(Comparator.comparingLong(binding -> binding.controller().asLong())).toList();
  }

  public static ListTag write(Collection<UpgradeBusBinding> bindings) {
    if (bindings.size() > MAX_BINDINGS) {
      throw new IllegalArgumentException("Upgrade bus binding limit exceeded");
    }
    var result = new ListTag();
    for (var binding : ordered(bindings)) {
      var tag = new CompoundTag();
      tag.putLong("controller", binding.controller().asLong());
      tag.putString("machine", binding.machine().toString());
      result.add(tag);
    }

    return result;
  }

  public static Map<BlockPos, UpgradeBusBinding> read(ListTag values) {
    if (values.size() > MAX_BINDINGS || !values.isEmpty() && values.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid upgrade bus binding list");
    }
    var result = new LinkedHashMap<BlockPos, UpgradeBusBinding>();
    for (var value : values) {
      var tag = (CompoundTag) value;
      if (!tag.getAllKeys().equals(Set.of("controller", "machine"))
          || !tag.contains("controller", Tag.TAG_LONG)
          || !tag.contains("machine", Tag.TAG_STRING)) {
        throw new IllegalArgumentException("Invalid upgrade bus binding fields");
      }
      var binding = new UpgradeBusBinding(
          BlockPos.of(tag.getLong("controller")),
          ResourceLocation.parse(tag.getString("machine")));
      if (result.putIfAbsent(binding.controller(), binding) != null) {
        throw new IllegalArgumentException("Duplicate upgrade bus controller binding");
      }
    }

    return Map.copyOf(result);
  }
}
