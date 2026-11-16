package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeItemDataTest {
  private static final ResourceLocation ID = ResourceLocation.parse("test:data");

  @Test
  void updatesOwnNestedValuesAndAllowNoncyclicSharedInputNodes() {
    var nested = new CompoundTag();
    nested.putInt("value", 7);
    var data = new CompoundTag();
    data.put("left", nested);
    data.put("right", nested);
    var update = new UpgradeItemData.Update(data);
    nested.putInt("value", 99);
    update.data().getCompound("left").putInt("value", 11);
    var owned = update.data();

    assertEquals(7, owned.getCompound("left").getInt("value"));
    assertEquals(7, owned.getCompound("right").getInt("value"));
    assertNotSame(owned.getCompound("left"), owned.getCompound("right"));
  }

  @Test
  void rejectsCyclicContainersBeforeAttemptingCopyOrSerialization() {
    var compound = new CompoundTag();
    compound.put("self", compound);
    assertThrows(IllegalArgumentException.class, () -> new UpgradeItemData.Update(compound));
    var list = new ListTag();
    list.add(list);
    var root = new CompoundTag();
    root.put("list", list);
    assertThrows(IllegalArgumentException.class, () -> new UpgradeItemData.Update(root));
  }

  @Test
  void boundsPayloadBytesTagCountAndDepth() {
    var oversized = new CompoundTag();
    oversized.putByteArray("payload", new byte[UpgradeItemData.MAX_BYTES]);
    assertThrows(IllegalArgumentException.class, () -> new UpgradeItemData.Update(oversized));
    var many = new CompoundTag();
    for (int index = 0; index < UpgradeItemData.MAX_TAGS; index++) {
      many.putInt("key" + index, index);
    }
    assertThrows(IllegalArgumentException.class, () -> new UpgradeItemData.Update(many));
    var deep = new CompoundTag();
    var current = deep;
    for (int index = 0; index <= UpgradeItemData.MAX_DEPTH; index++) {
      var next = new CompoundTag();
      current.put("child", next);
      current = next;
    }
    assertThrows(IllegalArgumentException.class, () -> new UpgradeItemData.Update(deep));
  }

  @Test
  void contextOwnsPositionAndAllowsIntSizedBatches() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var context = new UpgradeItemData.Context(
        ID,
        ID,
        ID,
        position,
        "main",
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        UpgradeSnapshot.identity(0));
    position.set(7, 8, 9);

    assertEquals(new BlockPos(1, 2, 3), context.controllerPosition());
    assertEquals(Integer.MAX_VALUE, context.instances());
    for (String thread : List.of("", "bad thread", "a".repeat(65))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new UpgradeItemData.Context(ID, ID, ID, BlockPos.ZERO, thread, 1, 0, UpgradeSnapshot.identity(0)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeItemData.Context(ID, ID, ID, BlockPos.ZERO, "main", 0, 0, UpgradeSnapshot.identity(0)));
  }
}
