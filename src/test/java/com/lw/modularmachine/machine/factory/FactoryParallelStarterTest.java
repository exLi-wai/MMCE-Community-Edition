package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.FactoryParallelStarter.Admission;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter.Result;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryParallelStarterTest {
  @Test
  void admissionRecordRejectsMismatchedPayload() {
    assertThrows(IllegalArgumentException.class, () -> new Admission(Result.ADMITTED, null));
  }
}
