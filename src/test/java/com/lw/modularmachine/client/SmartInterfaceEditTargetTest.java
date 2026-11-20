package com.lw.modularmachine.client;

import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.lw.modularmachine.network.SmartInterfacePayloads;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmartInterfaceEditTargetTest {
  private static final SmartInterfaceType TYPE = new SmartInterfaceType("speed", 1, 0, 10, "", "", "");
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:test");
  private static final UUID INSTANCE = new UUID(0, 1);

  private static SmartInterfaceBlockEntity.Entry entry(BlockPos owner, long revision) {
    return new SmartInterfaceBlockEntity.Entry(
        new SmartInterfaceBinding(owner, MACHINE, "speed", 1, revision),
        "test",
        TYPE,
        true);
  }

  @Test
  void staleDraftCannotFollowAReboundInterfaceOrNewInstance() {
    var original = entry(BlockPos.ZERO, 1);
    var target = new SmartInterfaceScreen.EditTarget(INSTANCE, original);
    var rebound = entry(BlockPos.ZERO, 3);
    var updated = new SmartInterfacePayloads.Status(1, BlockPos.ZERO, INSTANCE, 3, true, List.of(rebound));
    assertFalse(target.matches(updated, rebound));
    var replaced = new SmartInterfacePayloads.Status(1, BlockPos.ZERO, new UUID(0, 2), 1, true, List.of(original));
    assertFalse(target.matches(replaced, original));
  }

  @Test
  void anotherBindingCanChangeWithoutInvalidatingTheSelectedBinding() {
    var target = new SmartInterfaceScreen.EditTarget(INSTANCE, entry(BlockPos.ZERO, 1));
    var selected = entry(BlockPos.ZERO, 1);
    var other = entry(BlockPos.ZERO.east(), 4);
    var updated = new SmartInterfacePayloads.Status(1, BlockPos.ZERO, INSTANCE, 4, true, List.of(other, selected));
    assertTrue(target.matches(updated, selected));
    assertFalse(target.matches(updated, other));
    assertFalse(target.matches(updated, null));
  }
}
