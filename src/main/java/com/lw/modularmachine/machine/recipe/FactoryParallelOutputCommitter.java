package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryRunSet;

/** Parallel facade over the same factory-owned resumable output transaction. */
public final class FactoryParallelOutputCommitter {
  private FactoryParallelOutputCommitter() {
  }

  public static boolean flush(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set) {
    return factory.advanceOutput(threadId, set) == FactoryOutputEngine.Result.COMMITTED;
  }
}
