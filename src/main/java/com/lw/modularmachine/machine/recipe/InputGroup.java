package com.lw.modularmachine.machine.recipe;

/** Numeric input isolation is independent of the component selector. */
public final class InputGroup {
  public static final int SHARED = -1;

  private InputGroup() {
  }

  public static int require(int group) {
    if (group != SHARED && group <= 0) {
      throw new IllegalArgumentException("Input group must be -1 or positive");
    }

    return group;
  }

  public static boolean includes(int selected, int portGroup) {
    return portGroup == SHARED || selected == portGroup;
  }
}
