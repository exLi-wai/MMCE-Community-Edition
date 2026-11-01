package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.machine.factory.FactoryRunSetCodec;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryRunSetCodecTest {
  @Test
  void roundTripsFrozenRuns() {
    var run = FrozenItemRun.read(FrozenItemRunTest.valid());
    var restored = FactoryRunSetCodec.read(FactoryRunSetCodec.write(FactoryRunSet.empty().add(run)), null);
    assertEquals(run.runId(), restored.runs().get(0).runId());
    assertEquals(run.save(), restored.runs().get(0).save());
  }

  @Test
  void persistsMoreThanTheFormerRunLimit() {
    var runs = new ArrayList<FrozenItemRun>();
    for (int index = 0; index < 300; index++) {
      var data = FrozenItemRunTest.valid();
      data.putUUID("run_id", new UUID(2, index + 1));
      runs.add(FrozenItemRun.read(data));
    }

    var restored = FactoryRunSetCodec.read(FactoryRunSetCodec.write(FactoryRunSet.ofRuns(runs)), null);
    assertEquals(300, restored.runs().size());
    assertEquals(runs.getLast().runId(), restored.runs().getLast().runId());
  }

  @Test
  void rejectsWrongEntryShape() {
    var list = new ListTag();
    list.add(StringTag.valueOf("wrong"));
    assertThrows(IllegalArgumentException.class, () -> FactoryRunSetCodec.read(list, null));
  }
}
