package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.factory.FactoryRunState;
import com.lw.modularmachine.machine.factory.FactoryThreadConfiguration;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FactoryRunStateTest {
  private static FrozenRecipeRun run(int id) {
    var source = FrozenRecipeRunTest.valid();
    source.putUUID("run_id", new UUID(1, id));
    return FrozenRecipeRun.read(source);
  }

  @Test
  void failedAdmissionReleasesStartAndSuccessfulAdmissionFreezesItsLimit() {
    var host = new Host();
    host.failLimit = true;
    assertFalse(host.runs.beginRunStart("a"));
    assertTrue(host.runs.startingThreads().isEmpty());
    host.failLimit = false;
    assertTrue(host.runs.beginRunStart("a"));
    assertFalse(host.runs.beginRunStart("a"));
    host.limit = 1;
    var batch = List.of(run(1), run(2));
    host.runs.installRuns("a", batch);
    host.runs.finishRunStart("a", true);
    assertEquals(batch, host.runs.runSet("a").runs());
    assertFalse(host.runs.beginRunStart("a"));
    assertTrue(host.runs.startingThreads().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> host.runs.view().clear());
  }

  @Test
  void rejectedInstallRetainsAnEmptyPaidSetAndReleasesOnResolve() {
    var host = new Host();
    assertTrue(host.runs.beginRunStart("a"));
    assertThrows(IllegalArgumentException.class, () -> host.runs.installRuns("a", List.of(run(1), run(2), run(3))));
    host.recipeAllowed = false;
    assertThrows(IllegalStateException.class, () -> host.runs.installRun("a", run(1)));
    assertTrue(host.runs.runSet("a").runs().isEmpty());
    host.runs.finishRunStart("a", false);
    assertTrue(host.runs.startingThreads().isEmpty());
  }

  @Test
  void replacementAndRemovalRequireTheExactPaidIdentity() {
    var host = new Host();
    var first = run(1);
    assertTrue(host.runs.beginRunStart("a"));
    host.runs.installRun("a", first);
    host.runs.finishRunStart("a", true);
    var original = host.runs.runSet("a");
    var advanced = first.advanced(1);
    assertTrue(host.runs.replaceRunIfSame("a", first, advanced));
    assertFalse(host.runs.removeRunIfSame("a", first));
    assertFalse(host.runs.removeRunSetIfSame("a", original));
    assertTrue(host.runs.removeRunIfSame("a", advanced));
    assertTrue(host.runs.view().isEmpty());
  }

  @Test
  void failureDecisionsReplaceOneWholeBatchAndRejectItsOldReference() {
    var host = new Host();
    var first = run(1);
    var second = run(2);
    assertTrue(host.runs.beginRunStart("a"));
    host.runs.installRuns("a", List.of(first, second));
    host.runs.finishRunStart("a", true);
    var original = host.runs.runSet("a");
    assertTrue(host.runs.applyRunFailureDecisionsIfSame("a", original, Map.of(), Set.of(first.runId())));
    assertEquals(List.of(second), host.runs.runSet("a").runs());
    assertFalse(host.runs.applyRunFailureDecisionsIfSame("a", original, Map.of(), Set.of(second.runId())));
    assertTrue(host.runs.applyRunFailureDecisionsIfSame("a", host.runs.runSet("a"), Map.of(), Set.of(second.runId())));
    assertTrue(host.runs.view().isEmpty());
  }

  private static final class Host implements FactoryThreadConfiguration.Host, FactoryRunState.Host {
    final FactoryThreadConfiguration threads = new FactoryThreadConfiguration(this);
    final FactoryRunState runs = new FactoryRunState(threads, this);
    int limit = 2;
    boolean failLimit;
    boolean recipeAllowed = true;

    Host() {
      threads.replaceThreads(
          List.of(
              new FactoryThreadState(
                  "a",
                  machineId(),
                  null,
                  true,
                  0,
                  1,
                  0,
                  FactoryThreadState.State.IDLE,
                  FactoryThreadState.Reason.NONE,
                  0,
                  UpgradeSnapshot.identity(0))));
    }

    @Override
    public void requireThread() {
    }
    @Override
    public void requireUpdateThread() {
    }
    @Override
    public void requireLoadAllowed() {
    }
    @Override
    public void refreshUpgrades() {
    }
    @Override
    public boolean validSources() {
      return true;
    }
    @Override
    public boolean startAllowed(String threadId) {
      return true;
    }
    @Override
    public boolean upgradesMatch(FactoryThreadState thread) {
      return true;
    }
    @Override
    public int parallelLimit(String threadId) {
      if (failLimit) {
        throw new IllegalStateException("Unavailable hardware");
      }
      return limit;
    }
    @Override
    public boolean recipeAllowed(String threadId, ResourceLocation recipeId, RecipeExecutionOptions options) {
      return recipeAllowed;
    }
    @Override
    public boolean persistentStateValid() {
      return true;
    }
    @Override
    public boolean current() {
      return true;
    }
    @Override
    public boolean dispatching() {
      return false;
    }
    @Override
    public ResourceLocation machineId() {
      return ResourceLocation.parse("mmcece:test");
    }
    @Override
    public boolean acceptsMachine(ResourceLocation machine) {
      return machineId().equals(machine);
    }
    @Override
    public void bindMachine(ResourceLocation machine) {
      throw new AssertionError("Unexpected bind");
    }
    @Override
    public boolean hasPaidRuns() {
      return !runs.view().isEmpty();
    }
    @Override
    public boolean hasPaidRuns(String threadId) {
      return !runs.runSet(threadId).runs().isEmpty();
    }
    @Override
    public boolean hasStartingThreads() {
      return !runs.startingThreads().isEmpty();
    }
    @Override
    public boolean threadBusy(String threadId) {
      return hasPaidRuns(threadId) || runs.startingThreads().contains(threadId);
    }
    @Override
    public int maximumThreads() {
      return 10;
    }
    @Override
    public boolean recipeBelongsTo(ResourceLocation machine, ResourceLocation recipe) {
      return true;
    }
    @Override
    public void beforeInstall() {
    }
    @Override
    public void installed() {
    }
    @Override
    public void threadChanged(String threadId) {
    }
    @Override
    public void markChanged() {
    }
  }
}
