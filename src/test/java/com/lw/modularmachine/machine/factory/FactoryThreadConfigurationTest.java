package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.factory.FactoryThreadConfiguration.Change;
import com.lw.modularmachine.machine.factory.FactoryThreadConfiguration.ConfigurationResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactoryThreadConfigurationTest {
  private static final ResourceLocation MACHINE = ResourceLocation.fromNamespaceAndPath("mmcece", "configuration_test");

  @Test
  void priorityEditPreservesRoundAndWaitingWithoutInvalidatingSearches() {
    var host = new Host();
    var configuration = host.configuration;
    configuration.replaceThreads(List.of(thread("a"), thread("b")));
    configuration.visitWindow(1);
    configuration.recordAttempt("b", false);
    var order = configuration.scheduler().roundOrder();
    var waiting = configuration.scheduler().waitingAges();
    long revision = configuration.revision();
    host.effects.clear();

    assertEquals(ConfigurationResult.SUCCESS, configuration.setThreadPriority("b", 50));
    assertEquals(revision + 1, configuration.revision());
    assertEquals(1, configuration.scheduler().cursor());
    assertEquals(order, configuration.scheduler().roundOrder());
    assertEquals(waiting, configuration.scheduler().waitingAges());
    assertEquals(List.of("dirty"), host.effects);
    host.effects.clear();
    assertEquals(ConfigurationResult.NO_CHANGE, configuration.setThreadPriority("b", 50));
    assertTrue(host.effects.isEmpty());
  }

  @Test
  void completeChangeCommitsOnceAndRejectsStaleOrPartialIntent() {
    var host = new Host();
    var configuration = host.configuration;
    configuration.replaceThreads(List.of(thread("a")));
    long revision = configuration.revision();
    long threadRevision = configuration.scheduler().channel("a").revision();
    host.effects.clear();
    assertTrue(configuration.update("a", threadRevision, new Change(true, MACHINE, false, 4, 1L)));
    var updated = configuration.scheduler().channel("a");
    assertEquals(MACHINE, updated.lockedRecipe());
    assertFalse(updated.enabled());
    assertEquals(4, updated.parallelLimit());
    assertEquals(revision + 1, configuration.revision());
    assertEquals(List.of("search:a", "dirty"), host.effects);
    host.effects.clear();
    assertFalse(configuration.update("a", threadRevision, Change.enabled(true)));
    assertFalse(configuration.update("a", updated.revision(), new Change(false, null, true, 2, null)));
    assertSame(updated, configuration.scheduler().channel("a"));
    assertTrue(host.effects.isEmpty());
    assertTrue(configuration.update("a", updated.revision(), Change.enabled(false)));
    assertTrue(host.effects.isEmpty());
  }

  @Test
  void paidAndStartingThreadsRetainTheirDifferentConfigurationRules() {
    var host = new Host();
    var configuration = host.configuration;
    configuration.replaceThreads(List.of(thread("a"), thread("b")));
    host.paid.add("a");
    long revision = configuration.scheduler().channel("a").revision();
    assertEquals(ConfigurationResult.BUSY, configuration.removeThread("a"));
    assertFalse(configuration.update("a", revision, Change.lockedRecipe(MACHINE)));
    assertTrue(configuration.update("a", revision, Change.enabled(false)));
    var retained = configuration.scheduler().channel("a");
    assertEquals(ConfigurationResult.SUCCESS, configuration.removeThread("b"));
    assertEquals(ConfigurationResult.SUCCESS, configuration.addThread("c", 0));
    assertSame(retained, configuration.scheduler().channel("a"));
    assertThrows(IllegalStateException.class, () -> configuration.replaceThreads(List.of()));
    host.starting.add("a");
    assertEquals(ConfigurationResult.BUSY, configuration.addThread("d", 0));
    assertEquals(ConfigurationResult.BUSY, configuration.removeThread("c"));
  }

  @Test
  void replacingWithDefaultThreadsRejectsRequestsFromBothPreviousStates() {
    var configuration = new Host().configuration;
    configuration.replaceThreads(List.of(thread("a")));
    long initialRevision = configuration.scheduler().channel("a").revision();
    assertTrue(configuration.update("a", initialRevision, Change.enabled(false)));
    long editedRevision = configuration.scheduler().channel("a").revision();
    configuration.replaceThreads(List.of(thread("a")));
    var replacement = configuration.scheduler().channel("a");

    assertTrue(replacement.revision() > editedRevision);
    assertFalse(configuration.update("a", initialRevision, Change.enabled(false)));
    assertFalse(configuration.update("a", editedRevision, Change.lockedRecipe(MACHINE)));
    assertSame(replacement, configuration.scheduler().channel("a"));
    assertTrue(configuration.update("a", replacement.revision(), Change.enabled(false)));
  }

  @Test
  void sameIdRecreatedAfterSavedRemovalExceedsRetiredRuntimeRevision() {
    var configuration = new Host().configuration;
    configuration.replaceThreads(List.of(thread("a")));
    var retired = configuration.scheduler().channel("a");
    for (int priority = 1; priority <= 7; priority++) {
      retired = retired.withPriority(priority);
    }
    configuration.replaceRuntime(configuration.scheduler().replace(retired));
    assertEquals(ConfigurationResult.SUCCESS, configuration.removeThread("a"));
    var saved = new CompoundTag();
    configuration.save(saved);
    var restored = new Host().configuration;
    restored.load(saved);
    assertEquals(ConfigurationResult.SUCCESS, restored.addThread("a", 0));
    var replacement = restored.scheduler().channel("a");

    assertTrue(replacement.revision() > retired.revision());
    assertFalse(restored.update("a", retired.revision(), Change.enabled(false)));
    assertTrue(restored.update("a", replacement.revision(), Change.enabled(false)));
  }

  @Test
  void loweringLimitAllowsOnlyShrinkingAndRetainsRetiredRevisionWatermark() {
    var host = new Host();
    var configuration = host.configuration;
    configuration.replaceThreads(List.of(thread("a"), thread("b"), thread("c")));
    var retired = thread("c");
    for (int i = 1; i <= 7; i++) {
      retired = retired.withPriority(i);
    }
    configuration.replaceRuntime(configuration.scheduler().replace(retired));
    host.maximum = 1;
    host.effects.clear();
    long before = configuration.revision();
    assertEquals(ConfigurationResult.SUCCESS, configuration.removeThread("c"));
    assertEquals(8, configuration.revision());
    assertEquals(List.of("before:" + before, "installed:8"), host.effects);
    assertEquals(ConfigurationResult.LIMIT_REACHED, configuration.addThread("d", 0));
    assertThrows(IllegalArgumentException.class, () -> configuration.replaceThreads(List.of(thread("a"), thread("d"))));
    assertEquals(ConfigurationResult.SUCCESS, configuration.removeThread("b"));
    assertTrue(configuration.revision() > 8);
  }

  @Test
  void persistenceRetainsFairnessAndWatermarkWithoutConfigurationEffects() {
    var host = new Host();
    host.configuration.replaceThreads(List.of(thread("a"), thread("b")));
    host.configuration.visitWindow(1);
    host.configuration.recordAttempt("b", false);
    var saved = new CompoundTag();
    host.configuration.save(saved);
    var restored = new Host();
    restored.configuration.load(saved);
    assertTrue(restored.configuration.initialized());
    assertEquals(host.configuration.revision(), restored.configuration.revision());
    assertEquals(host.configuration.scheduler().channels(), restored.configuration.scheduler().channels());
    assertEquals(1, restored.configuration.scheduler().cursor());
    assertEquals(host.configuration.scheduler().roundOrder(), restored.configuration.scheduler().roundOrder());
    assertEquals(host.configuration.scheduler().waitingAges(), restored.configuration.scheduler().waitingAges());
    assertTrue(restored.effects.isEmpty());
    var original = restored.configuration.scheduler();
    saved.getCompound("scheduler_waiting").put("b", StringTag.valueOf("bad"));
    assertThrows(IllegalArgumentException.class, () -> restored.configuration.load(saved));
    assertSame(original, restored.configuration.scheduler());
    restored.configuration.resetState();
    assertFalse(restored.configuration.initialized());
    assertEquals(0, restored.configuration.revision());
    assertTrue(restored.configuration.scheduler().channels().isEmpty());
  }

  private static FactoryThreadState thread(String id) {
    return new FactoryThreadState(
        id,
        MACHINE,
        null,
        true,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        UpgradeSnapshot.identity(0));
  }

  private static final class Host implements FactoryThreadConfiguration.Host {
    private final FactoryThreadConfiguration configuration = new FactoryThreadConfiguration(this);
    private final List<String> effects = new ArrayList<>();
    private final Set<String> paid = new HashSet<>();
    private final Set<String> starting = new HashSet<>();
    private int maximum = 10;
    @Override
    public void requireThread() {
    }
    @Override
    public void requireUpdateThread() {
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
      return MACHINE;
    }
    @Override
    public boolean acceptsMachine(ResourceLocation machine) {
      return MACHINE.equals(machine);
    }
    @Override
    public void bindMachine(ResourceLocation machine) {
      throw new AssertionError("Unexpected bind");
    }
    @Override
    public boolean hasPaidRuns() {
      return !paid.isEmpty();
    }
    @Override
    public boolean hasPaidRuns(String threadId) {
      return paid.contains(threadId);
    }
    @Override
    public boolean hasStartingThreads() {
      return !starting.isEmpty();
    }
    @Override
    public boolean threadBusy(String threadId) {
      return paid.contains(threadId) || starting.contains(threadId);
    }
    @Override
    public int maximumThreads() {
      return maximum;
    }
    @Override
    public boolean recipeBelongsTo(ResourceLocation machine, ResourceLocation recipe) {
      return true;
    }
    @Override
    public void beforeInstall() {
      effects.add("before:" + configuration.revision());
    }
    @Override
    public void installed() {
      effects.add("installed:" + configuration.revision());
    }
    @Override
    public void threadChanged(String threadId) {
      effects.add("search:" + threadId);
    }
    @Override
    public void markChanged() {
      effects.add("dirty");
    }
  }
}
