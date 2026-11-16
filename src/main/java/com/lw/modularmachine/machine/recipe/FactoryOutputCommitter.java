package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;

/** Single-run facade over the factory-owned resumable output transaction. */
public final class FactoryOutputCommitter {
  private FactoryOutputCommitter() {
  }

  public static boolean flush(FactoryControllerBlockEntity factory, String threadId, FrozenRecipeRun run) {
    var set = factory.runSet(threadId);
    if (set.runs().size() != 1 || set.runs().getFirst() != run) {
      return false;
    }

    return factory.runCoordinator().advanceOutput(threadId, set) == FactoryOutputEngine.Result.COMMITTED;
  }
}
