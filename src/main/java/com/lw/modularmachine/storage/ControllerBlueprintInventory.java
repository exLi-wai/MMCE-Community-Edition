package com.lw.modularmachine.storage;

import com.lw.modularmachine.item.MachineBlueprintItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/** One original-style blueprint slot owned by a controller block entity. */
public final class ControllerBlueprintInventory extends ItemStackHandler {
  private final Runnable beforeWrite;
  private final Runnable changed;
  private Tag opaque;

  public ControllerBlueprintInventory(Runnable beforeWrite, Runnable changed) {
    super(1);
    this.beforeWrite = beforeWrite;
    this.changed = changed;
  }

  public boolean valid() {
    return opaque == null;
  }

  public ResourceLocation machine() {
    var stack = getStackInSlot(0);
    return stack.getItem() instanceof MachineBlueprintItem item ? item.machineId() : null;
  }

  private void writeAllowed() {
    beforeWrite.run();
    if (!valid()) {
      throw new IllegalStateException("Cannot edit unsupported controller blueprint data");
    }
  }

  @Override
  public void setStackInSlot(int slot, ItemStack stack) {
    writeAllowed();
    if (!stack.isEmpty() && (!isItemValid(slot, stack) || stack.getCount() > stack.getMaxStackSize())) {
      throw new IllegalArgumentException("Invalid controller blueprint stack");
    }
    super.setStackInSlot(slot, stack);
  }

  @Override
  public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    if (!simulate) {
      writeAllowed();
    }
    return super.insertItem(slot, stack, simulate);
  }

  @Override
  public ItemStack extractItem(int slot, int amount, boolean simulate) {
    if (!simulate) {
      writeAllowed();
    }
    return super.extractItem(slot, amount, simulate);
  }

  @Override
  public boolean isItemValid(int slot, ItemStack stack) {
    return slot == 0 && stack.getItem() instanceof MachineBlueprintItem;
  }

  @Override
  public int getSlotLimit(int slot) {
    return 16;
  }

  @Override
  protected void onContentsChanged(int slot) {
    changed.run();
  }

  /**
   * Vanilla slot merging can mutate a stack in place instead of calling
   * setStackInSlot.
   */
  public void markChanged() {
    writeAllowed();
    changed.run();
  }

  public Tag save(HolderLookup.Provider registries) {
    return opaque == null ? super.serializeNBT(registries) : opaque.copy();
  }

  public void load(HolderLookup.Provider registries, Tag tag) {
    beforeWrite.run();
    opaque = null;
    stacks.set(0, ItemStack.EMPTY);
    if (tag == null) {
      return;
    }
    try {
      if (!(tag instanceof CompoundTag data) || data.getInt("Size") != 1) {
        throw new IllegalArgumentException("Invalid controller blueprint slot count");
      }
      var loaded = new ItemStackHandler(1);
      loaded.deserializeNBT(registries, data);
      var stack = loaded.getStackInSlot(0);
      if (!stack.isEmpty() && (!isItemValid(0, stack) || stack.getCount() > stack.getMaxStackSize())) {
        throw new IllegalArgumentException("Invalid saved controller blueprint");
      }
      stacks.set(0, stack);
    } catch (RuntimeException invalid) {
      opaque = tag.copy();
    }
  }
}
