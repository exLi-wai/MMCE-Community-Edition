package com.lw.modularmachine.machine.factory;

/** Pure resource-based parallel-instance admission calculation. */
public final class FactoryParallelism {
  private FactoryParallelism() {
  }

  public static int effectiveInstances(int parallelLimit, int inputCapacity, int outputCapacity, int budget) {
    if (parallelLimit < 1 || inputCapacity < 0 || outputCapacity < 0 || budget < 0) {
      throw new IllegalArgumentException("Invalid factory parallelism inputs");
    }
    return Math.min(Math.min(parallelLimit, inputCapacity), Math.min(outputCapacity, budget));
  }

  public static boolean canStart(int parallelLimit, int inputCapacity, int outputCapacity, int budget) {
    return effectiveInstances(parallelLimit, inputCapacity, outputCapacity, budget) > 0;
  }
}
