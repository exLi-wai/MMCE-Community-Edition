package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.api.upgrade.DynamicUpgrades;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeBusDataTest {
  private static final ResourceLocation A = ResourceLocation.parse("test:a");
  private static final ResourceLocation B = ResourceLocation.parse("test:b");

  @Test
  void statesOwnValuesAndClearOnlyTheSelectedDefinition() {
    var data = value(7);
    var update = new UpgradeBusData.Update(data);
    var state = UpgradeBusDataHandlers.State.empty().with(A, update).with(B, new UpgradeBusData.Update(value(9)));
    data.putLong("runs", 99);
    update.data().putLong("runs", 100);
    state.get(A).putLong("runs", 101);
    state.save().getCompound(A.toString()).putLong("runs", 102);
    assertEquals(7, state.get(A).getLong("runs"));
    assertSame(state, state.with(A, new UpgradeBusData.Update(value(7))));
    var loadedTag = state.save();
    var loaded = UpgradeBusDataHandlers.State.load(loadedTag);
    loadedTag.getCompound(A.toString()).putLong("runs", 103);
    assertEquals(7, loaded.get(A).getLong("runs"));
    var cleared = state.with(A, new UpgradeBusData.Update(new CompoundTag()));
    assertTrue(cleared.get(A).isEmpty());
    assertEquals(9, cleared.get(B).getLong("runs"));
    assertFalse(cleared.isEmpty());
    assertTrue(cleared.with(B, new UpgradeBusData.Update(new CompoundTag())).isEmpty());
  }

  @Test
  void groupedContextsAndReadOnlyEffectSnapshotsOwnTheirData() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var busData = value(4);
    var effect = new UpgradeEntry(A, 0, 1, 1, 1, 1, 1, 1);
    var upgrade = new UpgradeEvents.Upgrade(A, B, position, 0, 2, effect, new CompoundTag(), busData);
    var values = new ArrayList<>(List.of(upgrade));
    var subscription = new UpgradeBusDataHandlers.Subscription(B, A, position, values);
    var startup = new UpgradeItemData.Context(
        A,
        B,
        B,
        position,
        "main",
        Integer.MAX_VALUE,
        0,
        UpgradeSnapshot.identity(0));
    var context = new UpgradeBusData.Context(startup, A, position, values);
    var dynamic = new DynamicUpgrades.Context(A, B, A, position, 0, 2, 0, effect, new CompoundTag(), busData);
    values.clear();
    position.set(7, 8, 9);
    busData.putLong("runs", 99);
    upgrade.busData().putLong("runs", 100);
    dynamic.busData().putLong("runs", 101);
    assertEquals(new BlockPos(1, 2, 3), context.busPosition());
    assertEquals(1, subscription.upgrades().size());
    assertEquals(4, context.upgrades().getFirst().busData().getLong("runs"));
    assertEquals(4, dynamic.busData().getLong("runs"));
    assertThrows(UnsupportedOperationException.class, () -> context.upgrades().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusDataHandlers.Subscription(B, B, BlockPos.ZERO, List.of(upgrade)));
  }

  @Test
  void rejectsMalformedOrNoncanonicalPersistentEntries() {
    var malformed = new CompoundTag();
    malformed.putString(A.toString(), "NA");
    assertThrows(IllegalArgumentException.class, () -> UpgradeBusDataHandlers.State.load(malformed));
    for (String key : List.of("unqualified", "BAD ID")) {
      var root = new CompoundTag();
      root.put(key, value(1));
      assertThrows(IllegalArgumentException.class, () -> UpgradeBusDataHandlers.State.load(root));
    }
    var cyclic = new CompoundTag();
    cyclic.put(A.toString(), cyclic);
    assertThrows(IllegalArgumentException.class, () -> UpgradeBusDataHandlers.State.load(cyclic));
    var entries = new CompoundTag();
    for (int index = 0; index <= UpgradeBusDataHandlers.MAX_ENTRIES; index++) {
      entries.put("test:entry_" + index, value(index));
    }
    assertThrows(IllegalArgumentException.class, () -> UpgradeBusDataHandlers.State.load(entries));
  }

  @Test
  void appliesPerValueAndAggregateByteLimits() {
    var large = new CompoundTag();
    large.putByteArray("payload", new byte[UpgradeItemData.MAX_BYTES]);
    assertThrows(IllegalArgumentException.class, () -> new UpgradeBusData.Update(large));
    var payload = new CompoundTag();
    payload.putByteArray("payload", new byte[8000]);
    var update = new UpgradeBusData.Update(payload);
    var state = UpgradeBusDataHandlers.State.empty();
    for (int index = 0; index < 8; index++) {
      state = state.with(ResourceLocation.parse("test:entry_" + index), update);
    }
    var bounded = state;
    assertThrows(IllegalArgumentException.class, () -> bounded.with(A, update));
    assertEquals(8, bounded.save().size());
  }

  private static CompoundTag value(long count) {
    var data = new CompoundTag();
    data.putLong("runs", count);

    return data;
  }
}
