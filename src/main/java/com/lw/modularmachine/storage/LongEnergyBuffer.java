package com.lw.modularmachine.storage;

import java.util.function.LongSupplier;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Owned by the server thread; integer FE adapter over long storage and tick
 * limits.
 */
public final class LongEnergyBuffer implements IEnergyStorage {
  private final long capacity;
  private final long transferLimit;
  private final boolean input;
  private final LongSupplier clock;
  private final Runnable changed;
  private final Runnable beforeMutation;
  private long stored;
  private long budgetTick = Long.MIN_VALUE;
  private long received;
  private long extracted;
  private Lease active;

  public LongEnergyBuffer(long capacity, long transferLimit, boolean input, LongSupplier clock, Runnable changed) {
    this(capacity, transferLimit, input, clock, () -> {
    }, changed);
  }

  public LongEnergyBuffer(long capacity, long transferLimit, boolean input, LongSupplier clock, Runnable beforeMutation, Runnable changed) {
    if (capacity <= 0 || transferLimit <= 0) {
      throw new IllegalArgumentException("Invalid energy capacity/rate");
    }
    this.capacity = capacity;
    this.transferLimit = transferLimit;
    this.input = input;
    this.clock = clock;
    this.changed = changed;
    this.beforeMutation = beforeMutation;
  }

  private long receiveAllowance() {
    return transferLimit - (clock.getAsLong() == budgetTick ? received : 0);
  }

  private long extractAllowance() {
    return transferLimit - (clock.getAsLong() == budgetTick ? extracted : 0);
  }

  private void rollTick() {
    long now = clock.getAsLong();
    if (now != budgetTick) {
      budgetTick = now;
      received = 0;
      extracted = 0;
    }
  }

  @Override
  public int receiveEnergy(int amount, boolean simulate) {
    readAllowed();
    if (!input || amount <= 0) {
      return 0;
    }
    int accepted = (int) Math.min(amount, Math.min(capacity - stored, receiveAllowance()));
    if (!simulate && accepted > 0) {
      beforeMutation.run();
      rollTick();
      stored += accepted;
      received += accepted;
      changed.run();
    }
    return accepted;
  }

  @Override
  public int extractEnergy(int amount, boolean simulate) {
    readAllowed();
    if (input || amount <= 0) {
      return 0;
    }
    int accepted = (int) Math.min(amount, Math.min(stored, extractAllowance()));
    if (!simulate && accepted > 0) {
      beforeMutation.run();
      rollTick();
      stored -= accepted;
      extracted += accepted;
      changed.run();
    }
    return accepted;
  }

  public boolean transferExactTo(LongEnergyBuffer target, long amount) {
    readAllowed();
    target.readAllowed();
    if (!input || target.input || target == this || amount <= 0) {
      return false;
    }
    beforeMutation.run();
    target.beforeMutation.run();
    if (!canTransferExactTo(target, amount)) {
      return false;
    }
    rollTick();
    target.rollTick();
    stored -= amount;
    extracted += amount;
    target.stored += amount;
    target.received += amount;
    changed.run();
    target.changed.run();
    return true;
  }

  public boolean canTransferExactTo(LongEnergyBuffer target, long amount) {
    readAllowed();
    target.readAllowed();
    return input
        && !target.input
        && target != this
        && amount > 0
        && stored >= amount
        && extractAllowance() >= amount
        && target.capacity - target.stored >= amount
        && target.receiveAllowance() >= amount;
  }

  /**
   * Only used during deserialization, before this buffer is exposed to
   * capabilities.
   */
  public void load(long amount) {
    readAllowed();
    beforeMutation.run();
    stored = Math.max(0, Math.min(amount, capacity));
  }

  public long stored() {
    readAllowed();
    return stored;
  }

  public long extractableNow() {
    readAllowed();

    return Math.min(stored, Math.max(0, extractAllowance()));
  }

  public long receivableNow() {
    readAllowed();
    return Math.min(capacity - stored, Math.max(0, receiveAllowance()));
  }

  private void readAllowed() {
    if (active != null) {
      throw new IllegalStateException("Energy buffer is in an atomic recipe transaction");
    }
  }

  public Lease acquire() {
    readAllowed();
    beforeMutation.run();
    active = new Lease(stored, budgetTick, received, extracted);

    return active;
  }

  public final class Lease implements AutoCloseable {
    private final long beforeStored, beforeBudgetTick, beforeReceived, beforeExtracted;
    private boolean resolved;

    private Lease(long beforeStored, long beforeBudgetTick, long beforeReceived, long beforeExtracted) {
      this.beforeStored = beforeStored;
      this.beforeBudgetTick = beforeBudgetTick;
      this.beforeReceived = beforeReceived;
      this.beforeExtracted = beforeExtracted;
    }

    public boolean extractExact(long amount) {
      check();
      long now = clock.getAsLong();
      long available = Math.min(stored, now == budgetTick ? transferLimit - extracted : transferLimit);
      if (!input || amount <= 0 || amount > available) {
        return false;
      }
      if (now != budgetTick) {
        budgetTick = now;
        received = 0;
        extracted = 0;
      }
      stored -= amount;
      extracted += amount;

      return true;
    }

    /**
     * Machine production fills an output hatch with its long per-tick allowance.
     */
    public boolean insertExact(long amount) {
      check();
      long now = clock.getAsLong();
      long available = Math.min(capacity - stored, now == budgetTick ? transferLimit - received : transferLimit);
      if (input || amount <= 0 || amount > available) {
        return false;
      }
      if (now != budgetTick) {
        budgetTick = now;
        received = 0;
        extracted = 0;
      }
      stored += amount;
      received += amount;
      return true;
    }

    public void resolve(boolean commit) {
      check();
      if (!commit) {
        stored = beforeStored;
        budgetTick = beforeBudgetTick;
        received = beforeReceived;
        extracted = beforeExtracted;
      }
      active = null;
      resolved = true;
    }

    private void check() {
      if (resolved || active != this) {
        throw new IllegalStateException("Stale energy lease");
      }
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  public long capacity() {
    return capacity;
  }

  public long transferLimit() {
    return transferLimit;
  }

  @Override
  public int getEnergyStored() {
    readAllowed();

    return (int) Math.min(stored, Integer.MAX_VALUE);
  }

  @Override
  public int getMaxEnergyStored() {
    return (int) Math.min(capacity, Integer.MAX_VALUE);
  }

  @Override
  public boolean canExtract() {
    readAllowed();

    return !input;
  }

  @Override
  public boolean canReceive() {
    readAllowed();

    return input;
  }
}
