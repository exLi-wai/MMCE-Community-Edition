package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryThreadStateCodecTest {
  @Test
  void roundTripsThreadIntentAndRuntimeReason() {
    var thread = new FactoryThreadState(
        "smelt_1",
        ResourceLocation.fromNamespaceAndPath("mmcece", "smelter"),
        ResourceLocation.fromNamespaceAndPath("mmcece", "mmcece/iron"),
        false,
        7,
        2,
        4,
        FactoryThreadState.State.BLOCKED,
        FactoryThreadState.Reason.OUTPUT_BLOCKED,
        19,
        new FactoryUpgradeSnapshot(4, 2, 1, 1, 1, 1, 1, 1));

    assertEquals(List.of(thread), FactoryThreadStateCodec.read(FactoryThreadStateCodec.write(List.of(thread))));
  }

  @Test
  void rejectsMalformedAndOversizedThreadLists() {
    var malformed = new ListTag();
    malformed.add(new CompoundTag());
    assertThrows(IllegalArgumentException.class, () -> FactoryThreadStateCodec.read(malformed));
    assertThrows(IllegalArgumentException.class, () -> FactoryThreadStateCodec.read(new ListTag() {
      @Override
      public int size() {
        return FactoryThreadStateCodec.MAX_THREADS + 1;
      }
    }));
  }

  @Test
  void preservesAndValidatesUpgradeSnapshot() {
    var thread = new FactoryThreadState(
        "upgraded",
        ResourceLocation.fromNamespaceAndPath("mmcece", "machine"),
        null,
        true,
        0,
        4,
        8,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        2,
        new FactoryUpgradeSnapshot(8, 4, 1, 1, 1, 1, 1, 1));
    var encoded = FactoryThreadStateCodec.write(List.of(thread));
    assertEquals(thread, FactoryThreadStateCodec.read(encoded).get(0));
    encoded.getCompound(0).getCompound("upgrade_snapshot").putInt("parallel_limit", 7);
    assertThrows(IllegalArgumentException.class, () -> FactoryThreadStateCodec.read(encoded));
  }
}
