package com.lw.modularmachine.api.upgrade;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure bounded tooltip callbacks. Mutating returned item data does not write
 * the source stack.
 */
public final class UpgradeDescriptions {
  public static final int MAX_LINES = 32;
  public static final int MAX_LINE_LENGTH = 512;

  @FunctionalInterface
  public interface Handler {
    List<String> describe(Context context);
  }

  public record Context(
      UpgradeType type,
      ResourceLocation item,
      int stackSize,
      CompoundTag itemData,
      ResourceLocation machine,
      BlockPos busPosition) {
    public Context {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(item, "item");
      itemData = itemData.copy();
      busPosition = busPosition == null ? null : busPosition.immutable();
    }

    @Override
    public CompoundTag itemData() {
      return itemData.copy();
    }
    public UpgradeType getType() {
      return type;
    }
    public int getStackSize() {
      return stackSize;
    }
    public CompoundTag getCustomData() {
      return itemData();
    }
  }

  private UpgradeDescriptions() {
  }

  public static List<String> validate(List<String> lines) {
    Objects.requireNonNull(lines, "descriptions");
    if (lines.size() > MAX_LINES || lines.stream().anyMatch(line -> line == null || line.length() > MAX_LINE_LENGTH)) {
      throw new IllegalArgumentException("Upgrade descriptions exceed text limits");
    }
    return List.copyOf(lines);
  }
}
