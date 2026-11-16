package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.controller.ControllerRunState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerRunStateTest {
  private static final class Host implements ControllerRunState.Host {
    boolean serverThread = true;
    boolean current = true;
    ControllerRunState state;

    @Override
    public boolean onServerThread() {
      return serverThread;
    }

    @Override
    public boolean current() {
      return current;
    }

    @Override
    public ResourceLocation machineId() {
      return ResourceLocation.parse("mmcece:test");
    }

    @Override
    public void requireLoadAllowed() {
      if (!serverThread || state.busy()) {
        throw new IllegalStateException("Load during an active transaction");
      }
    }
  }

  private static ControllerRunState state(Host host) {
    host.state = new ControllerRunState(host);
    return host.state;
  }

  private static FrozenRecipeRun install(ControllerRunState state) {
    var run = FrozenRecipeRun.read(FrozenRecipeRunTest.valid());
    try (var lease = state.acquireStart()) {
      lease.install(run);
      lease.resolve(true);
    }
    return run;
  }

  @Test
  void failedStartRestoresIdleAndReleasesExclusiveLease() {
    var state = state(new Host());
    try (var lease = state.acquireStart()) {
      lease.install(FrozenRecipeRun.read(FrozenRecipeRunTest.valid()));
      assertTrue(state.busy());
      assertThrows(IllegalStateException.class, state::acquireStart);
    }
    assertNull(state.current());
    assertFalse(state.busy());
    assertSame(install(state), state.current());
  }

  @Test
  void updateRollbackRestoresExactPaidIdentityAndRejectsOldLease() {
    var state = state(new Host());
    var before = install(state);
    var lease = state.acquireUpdate(before);
    lease.install(before.advanced(1));
    lease.close();
    assertSame(before, state.current());
    assertFalse(state.busy());
    assertThrows(IllegalStateException.class, () -> lease.resolve(true));
    assertThrows(IllegalStateException.class, () -> state.acquireUpdate(before.advanced(1)));
  }

  @Test
  void committedProgressMustBeOneStepAndCannotClearUnfinishedRun() {
    var state = state(new Host());
    var before = install(state);
    var next = before.advanced(1);
    try (var lease = state.acquireUpdate(before)) {
      assertThrows(IllegalArgumentException.class, () -> lease.install(before.advanced(1, 2)));
      assertThrows(IllegalStateException.class, () -> lease.install(null));
      lease.install(next);
      lease.resolve(true);
    }
    assertSame(next, state.current());
  }

  @Test
  void outputClearingSharesTheUpdateCommitDecision() {
    var state = state(new Host());
    var source = FrozenRecipeRunTest.valid();
    source.putInt("progress", 20);
    var pending = FrozenRecipeRun.read(source).finished();
    try (var lease = state.acquireStart()) {
      lease.install(pending);
      lease.resolve(true);
    }
    try (var lease = state.acquireUpdate(pending)) {
      assertThrows(IllegalArgumentException.class, lease::installDiscard);
      lease.install(null);
    }
    assertSame(pending, state.current());
    try (var lease = state.acquireUpdate(pending)) {
      lease.install(null);
      lease.resolve(true);
    }
    assertNull(state.current());
  }

  @Test
  void loadAndOffThreadAccessCannotBreakAnActiveTransaction() {
    var host = new Host();
    var state = state(host);
    var before = install(state);
    try (var lease = state.acquireUpdate(before)) {
      assertThrows(IllegalStateException.class, () -> state.load(null, null));
      host.serverThread = false;
      assertThrows(IllegalStateException.class, () -> lease.install(before.advanced(1)));
      host.serverThread = true;
    }
    host.current = false;
    assertThrows(IllegalStateException.class, () -> state.acquireUpdate(before));
    assertSame(before, state.current());
  }

  @Test
  void failureAndDiscardRollBackUnlessExplicitlyResolved() {
    var state = state(new Host());
    var source = FrozenRecipeRunTest.valid();
    source.putInt("progress", 5);
    source.getCompound("definition").putString("failure_action", "reset");
    var before = FrozenRecipeRun.read(source);
    try (var lease = state.acquireStart()) {
      lease.install(before);
      lease.resolve(true);
    }
    try (var lease = state.acquireUpdate(before)) {
      lease.installFailure(before.afterResourceFailure(1));
    }
    assertSame(before, state.current());
    try (var lease = state.acquireUpdate(before)) {
      lease.installDiscard();
    }
    assertSame(before, state.current());
    try (var lease = state.acquireUpdate(before)) {
      lease.installDiscard();
      lease.resolve(true);
    }
    assertNull(state.current());
  }
}
