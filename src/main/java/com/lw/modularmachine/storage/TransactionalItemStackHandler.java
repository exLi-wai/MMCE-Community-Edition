package com.lw.modularmachine.storage;

import java.util.BitSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.ApiStatus;

/**
 * Owned inventory: no callbacks while swapping prepared contents; ordinary APIs
 * reject reentry.
 */
public final class TransactionalItemStackHandler extends ItemStackHandler {
  private final Runnable threadCheck;
  private final Runnable beforeWrite;
  private final Runnable changed;
  private final int slotLimit;
  private final int writableSlots;
  private Lease active;

  public TransactionalItemStackHandler(int size, Runnable threadCheck, Runnable beforeWrite, Runnable changed) {
    this(size, 64, threadCheck, beforeWrite, changed);
  }

  public TransactionalItemStackHandler(int size, int slotLimit, Runnable threadCheck, Runnable beforeWrite, Runnable changed) {
    super(size);
    if (slotLimit < 1) {
      throw new IllegalArgumentException("Invalid owned slot capacity");
    }
    this.slotLimit = slotLimit;
    writableSlots = size;
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
    return slot < writableSlots ? slotLimit : stacks.get(slot).getCount();
  }

  /** Configured receiving slots; retained tail slots are extraction-only. */
  public int writableSlots() {
    readAllowed();
    return writableSlots;
  }

  @Override
  protected int getStackLimit(int slot, ItemStack stack) {
    return slotLimit > 64 ? getSlotLimit(slot) : super.getStackLimit(slot, stack);
  }

  @Override
  public boolean isItemValid(int slot, ItemStack stack) {
    readAllowed();
    return slot < writableSlots && super.isItemValid(slot, stack);
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
    requireRetainedChange(slot, stacks.get(slot), stack);
    super.setStackInSlot(slot, stack);
  }

  @Override
  public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    if (simulate) {
      readAllowed();
    } else {
      writeAllowed();
    }
    validateSlotIndex(slot);
    return slot < writableSlots ? super.insertItem(slot, stack, simulate) : stack;
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
    if (slotLimit > 64) {
      var data = new CompoundTag();
      data.putInt("Size", stacks.size());
      var entries = new ListTag();
      for (int index = 0; index < stacks.size(); index++) {
        var stack = stacks.get(index);
        if (!stack.isEmpty()) {
          var entry = new CompoundTag();
          entry.putInt("Slot", index);
          entry.putInt("Amount", stack.getCount());
          entry.put("Prototype", stack.copyWithCount(1).save(registries));
          entries.add(entry);
        }
      }
      data.put("BulkItems", entries);
      return data;
    }
    return super.serializeNBT(registries);
  }

  /**
   * Restores a configurable ordinary bus only at its load boundary. Parse before
   * replacing contents, retain occupied slots beyond the new setting up to the
   * saved-format bound and never resize while a lease is active. The slot indices
   * remain stable until reload.
   */
  @ApiStatus.Internal
  public void deserializeConfiguredSlots(HolderLookup.Provider registries, CompoundTag data, int maximumWritableSlots, int maximumSavedSlots) {
    writeAllowed();
    int savedSlots = data.getInt("Size");
    if (slotLimit != 64
        || writableSlots < 1
        || writableSlots > maximumWritableSlots
        || maximumSavedSlots < maximumWritableSlots
        || !data.contains("Size", Tag.TAG_INT)
        || savedSlots < 1
        || savedSlots > maximumSavedSlots
        || data.contains("BulkItems")
        || data.contains("Items") && !data.contains("Items", Tag.TAG_LIST)) {
      throw new IllegalArgumentException("Invalid configured item bus inventory");
    }
    var raw = data.get("Items");
    if (raw instanceof ListTag list && !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Item slots require compound entries");
    }
    var entries = data.getList("Items", Tag.TAG_COMPOUND);
    if (entries.size() > savedSlots) {
      throw new IllegalArgumentException("Too many saved item slots");
    }
    var loaded = NonNullList.withSize(Math.max(writableSlots, savedSlots), ItemStack.EMPTY);
    var occupied = new BitSet(savedSlots);
    int retainedSlots = writableSlots;
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.getCompound(index);
      int slot = entry.getInt("Slot");
      if (!entry.contains("Slot", Tag.TAG_INT) || slot < 0 || slot >= savedSlots || occupied.get(slot)) {
        throw new IllegalArgumentException("Invalid or duplicate saved item slot");
      }
      occupied.set(slot);
      var stack = ItemStack.parse(registries, entry)
          .orElseThrow(() -> new IllegalArgumentException("Invalid saved item stack"));
      if (stack.isEmpty() || stack.getCount() > Math.min(slotLimit, stack.getMaxStackSize())) {
        throw new IllegalArgumentException("Saved item stack exceeds its slot limit");
      }
      loaded.set(slot, stack);
      retainedSlots = Math.max(retainedSlots, slot + 1);
    }
    var restored = NonNullList.withSize(retainedSlots, ItemStack.EMPTY);
    for (int slot = 0; slot < retainedSlots; slot++) {
      restored.set(slot, loaded.get(slot));
    }
    stacks = restored;
    onLoad();
    changed.run();
  }

  private void requireRetainedChange(int slot, ItemStack before, ItemStack after) {
    if (slot >= writableSlots
        && !after.isEmpty()
        && (before.isEmpty()
            || !ItemStack.isSameItemSameComponents(before, after)
            || after.getCount() > before.getCount())) {
      throw new IllegalArgumentException("Retained item slots only allow extraction");
    }
  }

  @Override
  public void deserializeNBT(HolderLookup.Provider registries, CompoundTag data) {
    writeAllowed();
    if (slotLimit > 64) {
      if (data.getInt("Size") != stacks.size() || !data.contains("BulkItems", Tag.TAG_LIST)) {
        throw new IllegalArgumentException("Invalid bulk inventory");
      }
      var contents = NonNullList.withSize(stacks.size(), ItemStack.EMPTY);
      var raw = (ListTag) data.get("BulkItems");
      if (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND) {
        throw new IllegalArgumentException("Bulk items require compound entries");
      }
      var entries = data.getList("BulkItems", Tag.TAG_COMPOUND);
      if (entries.size() > stacks.size()) {
        throw new IllegalArgumentException("Too many bulk slots");
      }
      for (int index = 0; index < entries.size(); index++) {
        var entry = entries.getCompound(index);
        int slot = entry.getInt("Slot");
        int amount = entry.getInt("Amount");
        if (!entry.contains("Slot", Tag.TAG_INT)
            || !entry.contains("Amount", Tag.TAG_INT)
            || slot < 0
            || slot >= stacks.size()
            || amount <= 0
            || amount > slotLimit
            || !contents.get(slot).isEmpty()) {
          throw new IllegalArgumentException("Invalid bulk slot");
        }
        var stack = ItemStack.parse(registries, entry.getCompound("Prototype"))
            .orElseThrow(() -> new IllegalArgumentException("Invalid bulk item prototype"));
        contents.set(slot, stack.copyWithCount(amount));
      }
      stacks = contents;
      onLoad();
      changed.run();
      return;
    }
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
      for (int slot = writableSlots; slot < before.size(); slot++) {
        requireRetainedChange(slot, before.get(slot), contents.get(slot));
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
