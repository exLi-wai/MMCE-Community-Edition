package com.lw.modularmachine.storage;

import com.lw.modularmachine.api.component.ChemicalStackData;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Owned long-quantity storage. Platform callbacks never run while a lease is
 * held.
 */
@ApiStatus.Internal
public final class TransactionalChemicalTank {
  private final long capacity;
  private final Runnable threadCheck;
  private final Runnable beforeWrite;
  private final Runnable changed;
  private ChemicalStackData stack = ChemicalStackData.EMPTY;
  private Lease active;

  public TransactionalChemicalTank(long capacity, Runnable threadCheck, Runnable beforeWrite, Runnable changed) {
    if (capacity < 0) {
      throw new IllegalArgumentException("Negative chemical capacity");
    }
    this.capacity = capacity;
    this.threadCheck = Objects.requireNonNull(threadCheck);
    this.beforeWrite = Objects.requireNonNull(beforeWrite);
    this.changed = Objects.requireNonNull(changed);
  }

  private void readAllowed() {
    threadCheck.run();
    if (active != null) {
      throw new IllegalStateException("Chemical tank is in an atomic recipe transaction");
    }
  }

  private void writeAllowed() {
    readAllowed();
    beforeWrite.run();
  }

  public ChemicalStackData getStack() {
    readAllowed();

    return stack;
  }

  public long getAmount() {
    readAllowed();

    return stack.amount();
  }

  public long getCapacity() {
    readAllowed();

    return capacity;
  }

  public long getSpace() {
    readAllowed();

    return Math.max(0, capacity - stack.amount());
  }

  public boolean isEmpty() {
    readAllowed();

    return stack.isEmpty();
  }

  public long fill(ChemicalStackData resource, boolean simulate) {
    Objects.requireNonNull(resource);
    if (simulate) {
      readAllowed();
    } else {
      writeAllowed();
    }
    if (resource.isEmpty() || !stack.isEmpty() && !stack.chemical().equals(resource.chemical())) {
      return 0;
    }
    long accepted = Math.min(resource.amount(), Math.max(0, capacity - stack.amount()));
    if (accepted > 0 && !simulate) {
      stack = resource.withAmount(stack.amount() + accepted);
      changed.run();
    }

    return accepted;
  }

  public ChemicalStackData drain(long amount, boolean simulate) {
    if (simulate) {
      readAllowed();
    } else {
      writeAllowed();
    }
    if (amount <= 0 || stack.isEmpty()) {
      return ChemicalStackData.EMPTY;
    }
    var extracted = stack.withAmount(Math.min(amount, stack.amount()));
    if (!simulate) {
      stack = stack.withAmount(stack.amount() - extracted.amount());
      changed.run();
    }

    return extracted;
  }

  public void setStack(ChemicalStackData value) {
    writeAllowed();
    Objects.requireNonNull(value);
    if (value.amount() > capacity) {
      throw new IllegalArgumentException("Chemical stack exceeds tank capacity");
    }
    if (!stack.equals(value)) {
      stack = value;
      changed.run();
    }
  }

  public CompoundTag save() {
    readAllowed();

    return stack.save();
  }

  /**
   * A lowered configuration capacity must not delete already stored chemicals.
   */
  public void load(CompoundTag tag) {
    writeAllowed();
    stack = ChemicalStackData.load(tag);
  }

  public boolean isInTransaction() {
    return active != null;
  }

  public Lease acquire() {
    writeAllowed();
    active = new Lease(stack);

    return active;
  }

  public final class Lease implements AutoCloseable {
    private final ChemicalStackData before;
    private boolean resolved;
    private boolean modified;

    private Lease(ChemicalStackData before) {
      this.before = before;
    }

    private void requireActive() {
      threadCheck.run();
      if (resolved || active != this) {
        throw new IllegalStateException("Stale chemical lease");
      }
    }

    public boolean drainExact(ChemicalStackData required) {
      requireActive();
      if (required == null
          || required.isEmpty()
          || stack.amount() < required.amount()
          || !stack.chemical().equals(required.chemical())) {
        return false;
      }
      stack = stack.withAmount(stack.amount() - required.amount());
      modified = true;

      return true;
    }

    public boolean fillExact(ChemicalStackData required) {
      requireActive();
      if (required == null
          || required.isEmpty()
          || Math.max(0, capacity - stack.amount()) < required.amount()
          || !stack.isEmpty() && !stack.chemical().equals(required.chemical())) {
        return false;
      }
      stack = required.withAmount(stack.amount() + required.amount());
      modified = true;

      return true;
    }

    public void resolve(boolean commit) {
      requireActive();
      if (!commit) {
        stack = before;
      }
      active = null;
      resolved = true;
    }

    public boolean modified() {
      return modified;
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }
}
