package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactorySchedulerTest {
  private static FactoryThreadState channel(String id, boolean enabled) {
    return new FactoryThreadState(
        id,
        ResourceLocation.fromNamespaceAndPath("mmcece", "processor"),
        null,
        enabled,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        FactoryUpgradeSnapshot.identity(0));
  }

  @Test
  void rotatesFairlyAndStartsAtMostOneChannelPerAdvance() {
    var scheduler = new FactoryScheduler(List.of(channel("b", true), channel("a", true)));
    var first = scheduler.advance(8, ignored -> FactoryScheduler.AttemptResult.STARTED);
    assertEquals(List.of("a"), first.visited());
    assertEquals("a", first.started().threadId());
    var second = scheduler.advance(8, ignored -> FactoryScheduler.AttemptResult.STARTED);
    assertEquals(List.of("b"), second.visited());
    assertEquals("b", second.started().threadId());
  }

  @Test
  void skipsDisabledAndContinuesAfterBlockedAttemptWithinBudget() {
    var scheduler = new FactoryScheduler(List.of(channel("a", false), channel("b", true), channel("c", true)));
    var report = scheduler.advance(3, ignored -> FactoryScheduler.AttemptResult.WAITING);
    assertEquals(List.of("b", "c"), report.visited());
    assertEquals(3, report.workUsed());
    assertEquals(0, report.nextCursor());
  }

  @Test
  void rejectsDuplicateChannelsAndNegativeBudget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryScheduler(List.of(channel("a", true), channel("a", true))));
    var scheduler = new FactoryScheduler(List.of(channel("a", true)));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheduler.advance(-1, ignored -> FactoryScheduler.AttemptResult.WAITING));
  }

  @Test
  void restoresAndValidatesSchedulerCursor() {
    var restored = new FactoryScheduler(List.of(channel("a", true), channel("b", true)), 1);
    assertEquals(1, restored.cursor());
    assertEquals(List.of("b"), restored.advance(1, ignored -> FactoryScheduler.AttemptResult.STARTED).visited());
    assertThrows(IllegalArgumentException.class, () -> new FactoryScheduler(List.of(channel("a", true)), 1));
  }

  @Test
  void replacesOneChannelWithoutChangingTheRoundCursor() {
    var scheduler = new FactoryScheduler(List.of(channel("a", true), channel("b", true)), 1);
    var updated = scheduler.replace(
        channel("b", true).withState(FactoryThreadState.State.WAITING, FactoryThreadState.Reason.STRUCTURE_PENDING));
    assertEquals(1, updated.cursor());
    assertEquals(FactoryThreadState.State.WAITING, updated.channels().get(1).state());
    assertEquals(1, updated.channels().get(1).revision());
  }

  @Test
  void parallelLimitChangesResetTransientStateAndAdvanceUpgradeRevision() {
    var channel = channel("parallel", true)
        .withState(FactoryThreadState.State.WAITING, FactoryThreadState.Reason.SEARCH_BUDGET);
    var updated = channel.withParallelLimit(8, 3);
    assertEquals(8, updated.parallelLimit());
    assertEquals(3, updated.upgradeRevision());
    assertEquals(FactoryThreadState.State.IDLE, updated.state());
    assertEquals(FactoryThreadState.Reason.NONE, updated.reason());
    assertThrows(IllegalArgumentException.class, () -> updated.withParallelLimit(2, 2));
  }
}
