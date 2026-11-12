package com.lw.modularmachine.machine.orientation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerPlacementDataTest {
  private CompoundTag root(String machine) {
    CompoundTag root = new CompoundTag(), data = new CompoundTag();
    data.putInt("schema", 1);
    data.putString("machine", machine);
    root.put("mmcece", data);
    return root;
  }

  @Test
  void absentOldDataIsUnboundInsteadOfAssumingAMachine() {
    var parsed = ControllerPlacementData.fromTag(new CompoundTag());
    assertFalse(parsed.opaque());
    assertEquals(ControllerPlacementData.UNBOUND_MACHINE, parsed.machineId());
  }

  @Test
  void schemaTwoBindingIsSupportedButOldSchemaCannotHideARun() {
    var current = root("mmcece:test");
    current.getCompound("mmcece").putInt("schema", 2);
    assertFalse(ControllerPlacementData.fromTag(current).opaque());
    current.getCompound("mmcece").putLong("future_output", 1);
    assertTrue(ControllerPlacementData.fromTag(current).opaque());
    var bad = root("mmcece:test");
    bad.getCompound("mmcece").put("run", new CompoundTag());
    var before = bad.copy();
    assertTrue(ControllerPlacementData.fromTag(bad).opaque());
    assertEquals(before, bad);
  }

  @Test
  void knownAndTemporarilyUnknownValidIdsRemainDistinctFromTheDefault() {
    for (String id : new String[]{"mmcece:transformer", "mmcece:custom_machine", "another_pack:missing_machine"}) {
      var parsed = ControllerPlacementData.fromTag(root(id));
      assertFalse(parsed.opaque());
      assertEquals(ResourceLocation.parse(id), parsed.machineId());
    }
  }

  @Test
  void malformedIdsAndWrongTypesAreOpaqueRatherThanDefaultMachines() {
    for (String id : new String[]{"", "INVALID ID", "BadNamespace:machine"}) {
      assertTrue(ControllerPlacementData.fromTag(root(id)).opaque(), id);
    }
    var wrongType = root("mmcece:test");
    wrongType.getCompound("mmcece").putInt("machine", 7);
    assertTrue(ControllerPlacementData.fromTag(wrongType).opaque());
    var wrongRoot = new CompoundTag();
    wrongRoot.put("mmcece", StringTag.valueOf("preserve me"));
    assertTrue(ControllerPlacementData.fromTag(wrongRoot).opaque());
  }

  @Test
  void futureNegativeOrNonIntegralSchemaTagsAreOpaqueAndUnmodified() {
    for (int schema : new int[]{-1, 3, 99}) {
      var root = root("mmcece:test");
      root.getCompound("mmcece").putInt("schema", schema);
      var before = root.copy();
      assertTrue(ControllerPlacementData.fromTag(root).opaque());
      assertEquals(before, root);
    }
    var fractional = root("mmcece:test");
    fractional.getCompound("mmcece").putDouble("schema", 1.5);
    assertTrue(ControllerPlacementData.fromTag(fractional).opaque());
    var text = root("mmcece:test");
    text.getCompound("mmcece").putString("schema", "1");
    assertTrue(ControllerPlacementData.fromTag(text).opaque());
  }
}
