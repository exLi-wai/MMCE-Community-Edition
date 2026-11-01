package com.lw.modularmachine.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Owned inventory: no callbacks while swapping prepared contents; ordinary APIs
 * reject reentry.
 */
public final class TransactionalItemStackHandler extends ItemStackHandler {
  private final Runnable threadCheck;
  private final Runnable beforeWrite;
  private final Runnable changed;
  private Lease active;

  public TransactionalItemStackHandler(int size, Runnable threadCheck, Runnable beforeWrite, Runnable changed) {
    super(size);
    this.threadCheck = threadCheck;
    this.beforeWrite = beforeWrite;
    this.changed = changed;
  }

  private void readAllowed() {
    threadCheck.run();
    if (active != null) {
      throw new IllegalStateException("Inventory is in an atomic recipe transaction");
    }
  }

  private void writeAllowed() {
    readAllowed();
    beforeWrite.run();
  }

  public boolean isInTransaction() {
    threadCheck.run();
    return active != null;
  }

  @Override
  public int getSlots() {
    readAllowed();
    return super.getSlots();
  }

  @Override
  public ItemStack getStackInSlot(int slot) {
    readAllowed();
    return super.getStackInSlot(slot);
  }

  @Override
  public int getSlotLimit(int slot) {
    readAllowed();
    return super.getSlotLimit(slot);
  }

  @Override
  public boolean isItemValid(int slot, ItemStack stack) {
    readAllowed();
    return super.isItemValid(slot, stack);
  }

  @Override
  public void setSize(int size) {
    writeAllowed();
    if (size != stacks.size()) {
      throw new IllegalArgumentException("Owned item bus has a fixed slot count");
    }
    super.setSize(size);
  }

  @Override
  public void setStackInSlot(int slot, ItemStack stack) {
    writeAllowed();
    super.setStackInSlot(slot, stack);
  }

  @Override
  public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    if (simulate) {
      readAllowed();
    } else {
      writeAllowed();
    }
    return super.insertItem(slot, stack, simulate);
  }

  @Override
  public ItemStack extractItem(int slot, int amount, boolean simulate) {
    if (simulate) {
      readAllowed();
    } else {
      writeAllowed();
    }
    return super.extractItem(slot, amount, simulate);
  }

  @Override
  public CompoundTag serializeNBT(HolderLookup.Provider registries) {
    readAllowed();
    return super.serializeNBT(registries);
  }

  @Override
  public void deserializeNBT(HolderLookup.Provider registries, CompoundTag data) {
    writeAllowed();
    super.deserializeNBT(registries, data);
  }

  @Override
  protected void onContentsChanged(int slot) {
    changed.run();
  }

  /**
   * Caller prepares detached stacks before acquiring any lease. Only plain field
   * swaps follow.
   */
  public Lease acquire() {
    readAllowed();
    active = new Lease(stacks);
    return active;
  }

  public final class Lease implements AutoCloseable {
    private final NonNullList<ItemStack> before;
    private boolean resolved;

    private Lease(NonNullList<ItemStack> before) {
      this.before = before;
    }

    private void check() {
      threadCheck.run();
      if (resolved || active != this) {
        throw new IllegalStateException("Stale inventory lease");
      }
    }

    public void replacePrepared(NonNullList<ItemStack> contents) {
      check();
      if (contents == null || contents.size() != before.size()) {
        throw new IllegalArgumentException("Prepared slot count differs");
      }
      stacks = contents;
    }

    public void resolve(boolean commit) {
      check();
      if (!commit) {
        stacks = before;
      }
      active = null;
      resolved = true;
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }
}
