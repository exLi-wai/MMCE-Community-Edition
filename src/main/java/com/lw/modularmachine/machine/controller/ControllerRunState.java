package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import java.util.Objects;

/** Owns one ordinary paid run and its transactional start/update leases. */
@ApiStatus.Internal
public final class ControllerRunState {
  private final Host host;
  private FrozenRecipeRun runningRecipe;
  private RunStartLease recipeStart;
  private RunUpdateLease recipeUpdate;

  public interface Host {
    boolean onServerThread();
    boolean current();
    ResourceLocation machineId();
    void requireLoadAllowed();
  }

  public ControllerRunState(Host host) {
    this.host = Objects.requireNonNull(host, "host");
  }

  public FrozenRecipeRun current() {
    return runningRecipe;
  }

  public boolean busy() {
    return recipeStart != null || recipeUpdate != null || UpgradeLifecyclePublisher.isDispatchingAny();
  }

  /**
   * Restores only frozen paid data; callers retain invalid controller payloads as
   * opaque state.
   */
  public void load(CompoundTag source, HolderLookup.Provider registries) {
    host.requireLoadAllowed();
    runningRecipe = source != null && source.contains("run", Tag.TAG_COMPOUND) ? FrozenRecipeRun
        .read(source.getCompound("run"), registries) : null;
  }

  /**
   * No callbacks or persistence calls in lease operations; the transaction
   * publishes after resolving all ports.
   */
  public RunStartLease acquireStart() {
    if (!host.onServerThread()) {
      throw new IllegalStateException("Recipe start outside server thread");
    }
    if (busy() || runningRecipe != null || !host.current()) {
      throw new IllegalStateException("Controller cannot accept another recipe start");
    }
    recipeStart = new RunStartLease();
    return recipeStart;
  }

  public RunUpdateLease acquireUpdate(FrozenRecipeRun expected) {
    if (!host.onServerThread()) {
      throw new IllegalStateException("Run update outside server thread");
    }
    if (busy() || expected == null || runningRecipe != expected || !host.current()) {
      throw new IllegalStateException("Stale paid run owner");
    }
    recipeUpdate = new RunUpdateLease(expected);
    return recipeUpdate;
  }

  public final class RunUpdateLease implements AutoCloseable {
    private final FrozenRecipeRun before;
    private boolean resolved, installed;

    private RunUpdateLease(FrozenRecipeRun before) {
      this.before = before;
    }

    private void check() {
      if (!host.onServerThread() || resolved || recipeUpdate != this) {
        throw new IllegalStateException("Stale run update lease");
      }
    }

    public void install(FrozenRecipeRun replacement) {
      check();
      if (replacement == null) {
        if (!before.pendingOutput()) {
          throw new IllegalStateException("Unfinished run cannot be cleared");
        }
      } else {
        if (!before.sameRecipe(replacement)
            || replacement.progress() < before.progress()
            || (long) replacement.progress() > (long) before.progress() + 1
            || before.pendingOutput() && replacement != before) {
          throw new IllegalArgumentException("Invalid run state transition");
        }
      }
      runningRecipe = replacement;
      installed = true;
    }

    public void installFailure(FrozenRecipeRun replacement) {
      check();
      if (!before.sameRecipe(replacement)
          || before.pendingOutput()
          || replacement.pendingOutput()
          || replacement.progress() < 0
          || replacement.progress() > before.progress()) {
        throw new IllegalArgumentException("Invalid failed run state transition");
      }
      runningRecipe = replacement;
      installed = true;
    }

    public void installDiscard() {
      check();
      if (before.pendingOutput()) {
        throw new IllegalArgumentException("Cannot discard a pending output run");
      }
      runningRecipe = null;
      installed = true;
    }

    public void resolve(boolean commit) {
      check();
      if (commit && !installed) {
        throw new IllegalStateException("No prepared run update");
      }
      if (!commit) {
        runningRecipe = before;
      }
      recipeUpdate = null;
      resolved = true;
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  public final class RunStartLease implements AutoCloseable {
    private final FrozenRecipeRun before = runningRecipe;
    private boolean resolved;

    private void check() {
      if (!host.onServerThread() || resolved || recipeStart != this) {
        throw new IllegalStateException("Stale controller start lease");
      }
    }

    public void install(FrozenRecipeRun run) {
      check();
      if (run == null || !run.machineId().equals(host.machineId())) {
        throw new IllegalArgumentException("Wrong run owner");
      }
      runningRecipe = run;
    }

    public void resolve(boolean commit) {
      check();
      if (commit && runningRecipe == before) {
        throw new IllegalStateException("No installed recipe run");
      }
      if (!commit) {
        runningRecipe = before;
      }
      recipeStart = null;
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
