package com.lw.modularmachine.storage;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Owned fluid tank with short leases for atomic paid recipe ticks. */
public final class TransactionalFluidTank extends FluidTank {
  private final Runnable threadCheck;
  private final Runnable beforeWrite;
  private final Runnable changed;
  private Lease active;

  public TransactionalFluidTank(int capacity, Runnable threadCheck, Runnable beforeWrite, Runnable changed) {
    super(capacity);
    this.threadCheck = threadCheck;
    this.beforeWrite = beforeWrite;
    this.changed = changed;
  }

  private void readAllowed() {
    threadCheck.run();
    if (active != null) {
      throw new IllegalStateException("Fluid tank is in an atomic recipe transaction");
    }
  }

  private void writeAllowed() {
    readAllowed();
    beforeWrite.run();
  }

  @Override
  public FluidTank setCapacity(int capacity) {
    writeAllowed();
    if (this.capacity != capacity) {
      super.setCapacity(capacity);
      changed.run();
    }

    return this;
  }

  @Override
  public FluidTank setValidator(Predicate<FluidStack> validator) {
    readAllowed();

    return super.setValidator(validator);
  }

  @Override
  public FluidStack getFluid() {
    readAllowed();

    return fluid;
  }

  @Override
  public int getFluidAmount() {
    readAllowed();

    return fluid.getAmount();
  }

  @Override
  public FluidTank readFromNBT(HolderLookup.Provider registries, CompoundTag tag) {
    writeAllowed();

    return super.readFromNBT(registries, tag);
  }

  @Override
  public CompoundTag writeToNBT(HolderLookup.Provider registries, CompoundTag tag) {
    readAllowed();

    return super.writeToNBT(registries, tag);
  }

  @Override
  public int getTanks() {
    readAllowed();

    return super.getTanks();
  }

  @Override
  public FluidStack getFluidInTank(int tank) {
    readAllowed();

    return fluid;
  }

  @Override
  public boolean isFluidValid(int tank, FluidStack stack) {
    readAllowed();

    return super.isFluidValid(tank, stack);
  }

  @Override
  public int fill(FluidStack resource, FluidAction action) {
    if (action.execute()) {
      writeAllowed();
    } else {
      readAllowed();
    }

    return super.fill(resource, action);
  }

  @Override
  public FluidStack drain(FluidStack resource, FluidAction action) {
    if (action.execute()) {
      writeAllowed();
    } else {
      readAllowed();
    }

    return super.drain(resource, action);
  }

  @Override
  public FluidStack drain(int amount, FluidAction action) {
    if (action.execute()) {
      writeAllowed();
    } else {
      readAllowed();
    }

    return super.drain(amount, action);
  }

  @Override
  protected void onContentsChanged() {
    changed.run();
  }

  @Override
  public void setFluid(FluidStack stack) {
    writeAllowed();
    Objects.requireNonNull(stack, "stack");
    if (!FluidStack.matches(fluid, stack)) {
      super.setFluid(stack.copy());
      changed.run();
    }
  }

  @Override
  public boolean isEmpty() {
    readAllowed();

    return fluid.isEmpty();
  }

  @Override
  public int getSpace() {
    readAllowed();

    return Math.max(0, capacity - fluid.getAmount());
  }

  public Lease acquire() {
    readAllowed();
    beforeWrite.run();
    active = new Lease(fluid.copy());

    return active;
  }

  public boolean isInTransaction() {
    return active != null;
  }

  public final class Lease implements AutoCloseable {
    private final FluidStack before;
    private boolean resolved, modified;

    private Lease(FluidStack before) {
      this.before = before;
    }

    public boolean drainExact(FluidStack required) {
      threadCheck.run();
      if (resolved || active != this) {
        throw new IllegalStateException("Stale fluid lease");
      }
      if (required == null
          || required.isEmpty()
          || fluid.getAmount() < required.getAmount()
          || !FluidStack.isSameFluidSameComponents(fluid, required)) {
        return false;
      }
      fluid = fluid.copyWithAmount(fluid.getAmount() - required.getAmount());
      if (fluid.isEmpty()) {
        fluid = FluidStack.EMPTY;
      }
      modified = true;

      return true;
    }

    public boolean fillExact(FluidStack required) {
      threadCheck.run();
      if (resolved || active != this) {
        throw new IllegalStateException("Stale fluid lease");
      }
      if (required == null || required.isEmpty() || capacity - fluid.getAmount() < required.getAmount()) {
        return false;
      }
      if (!fluid.isEmpty() && !FluidStack.isSameFluidSameComponents(fluid, required)) {
        return false;
      }
      fluid = fluid.isEmpty() ? required.copy() : fluid.copyWithAmount(fluid.getAmount() + required.getAmount());
      modified = true;
      return true;
    }

    public void resolve(boolean commit) {
      threadCheck.run();
      if (resolved || active != this) {
        throw new IllegalStateException("Stale fluid lease");
      }
      if (!commit) {
        fluid = before.copy();
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
