package com.lw.modularmachine.machine.recipe.command;

import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.lw.modularmachine.api.recipe.RecipeCommandActions.Action;
import com.lw.modularmachine.api.recipe.RecipeCommandActions.Source;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ControllerCommandStateTest {
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:test_command_machine");
  private static final ResourceLocation RECIPE = ResourceLocation.parse("mmcece:test_command_recipe");

  @Test
  void claimedStartDoesNotReplayAfterPersistence() {
    var state = new ControllerCommandState();
    var request = pending(Type.STARTED, UUID.randomUUID(), 0, 10);
    assertEquals(ControllerCommandState.Admission.ACCEPTED, state.enqueue(request));
    assertEquals(request, state.claim());
    var restored = ControllerCommandState.load(state.save(false));
    assertTrue(restored.valid());
    assertFalse(restored.hasPending());
    assertEquals(ControllerCommandState.Admission.DUPLICATE, restored.enqueue(request));
    assertEquals(1, restored.status().claimed());
  }

  @Test
  void worldSaveRetainsOutboxButItemRestoreOnlyRetainsMarkers() {
    var state = new ControllerCommandState();
    var request = pending(Type.STARTED, UUID.randomUUID(), 0, 10);
    state.enqueue(request);
    var restored = ControllerCommandState.load(state.save(false));
    assertTrue(restored.hasPending());
    assertEquals(request, restored.claim());
    var item = new CompoundTag();
    item.put(ControllerCommandState.NBT_KEY, state.save(false));
    ControllerCommandState.sanitizeItemData(item);
    var placed = ControllerCommandState.load(item.get(ControllerCommandState.NBT_KEY));
    assertTrue(placed.valid());
    assertFalse(placed.hasPending());
    assertEquals(ControllerCommandState.Admission.DUPLICATE, placed.enqueue(request));
  }

  @Test
  void partialItemOutboxCannotBeCompletedByVanillaRecursiveMerge() {
    var forged = new ControllerCommandState();
    forged.enqueue(pending(Type.STARTED, UUID.randomUUID(), 0, 10));
    var partial = (CompoundTag) forged.save(false);
    partial.remove("schema");
    partial.remove("claimed");
    var item = new CompoundTag();
    item.put(ControllerCommandState.NBT_KEY, partial);
    ControllerCommandState.sanitizeItemData(item);
    var freshBlockEntityData = new CompoundTag();
    freshBlockEntityData.put(ControllerCommandState.NBT_KEY, new ControllerCommandState().save(false));
    freshBlockEntityData.merge(item);
    var restored = ControllerCommandState.load(freshBlockEntityData.get(ControllerCommandState.NBT_KEY));
    assertFalse(restored.valid());
    assertFalse(restored.hasPending());
  }

  @Test
  void tickRetryIsDeduplicatedWhileLaterRepeatedProgressRemainsEligible() {
    var state = new ControllerCommandState();
    var run = UUID.randomUUID();
    var first = pending(Type.TICK, run, 10, 10);
    assertEquals(ControllerCommandState.Admission.ACCEPTED, state.enqueue(first));
    assertEquals(ControllerCommandState.Admission.DUPLICATE, state.enqueue(first));
    assertEquals(ControllerCommandState.Admission.ACCEPTED, state.enqueue(pending(Type.TICK, run, 10, 11)));
    state.retire(run);
    assertEquals(ControllerCommandState.Admission.DUPLICATE, state.enqueue(pending(Type.TICK, run, 10, 12)));
  }

  @Test
  void completedHistoryIsBoundedAfterClaimsResolve() {
    var state = new ControllerCommandState();
    for (int index = 0; index < 1000; index++) {
      var request = pending(Type.FINISHED, UUID.randomUUID(), 10, index);
      assertEquals(ControllerCommandState.Admission.ACCEPTED, state.enqueue(request));
      state.retire(request.run());
      assertEquals(request, state.claim());
    }
    assertFalse(state.hasPending());
    assertTrue(state.status().tracked() <= 256);
    assertEquals(1000, state.status().claimed());
    assertTrue(ControllerCommandState.load(state.save(false)).valid());
  }

  private static ControllerCommandState.Pending pending(Type type, UUID run, int progress, long tick) {
    var action = new Action(
        ResourceLocation.parse("kubejs:test_" + type.name().toLowerCase(Locale.ROOT)),
        MACHINE,
        Optional.of(RECIPE),
        type,
        type == Type.TICK ? 10 : 1,
        "scoreboard players add command_test completed 1",
        Source.CONTROLLER,
        2);

    return new ControllerCommandState.Pending(
        action,
        run,
        RECIPE,
        progress,
        10000,
        tick,
        ResourceLocation.parse("minecraft:overworld"),
        new BlockPos(2, 64, 3),
        Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        action.command());
  }
}
