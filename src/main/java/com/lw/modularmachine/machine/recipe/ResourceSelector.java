package com.lw.modularmachine.machine.recipe;

/**
 * Disjoint resource groups. Empty means the unlabelled group, never a wildcard.
 */
public final class ResourceSelector {
  public static final int MAX_LENGTH = 64;

  private ResourceSelector() {
  }

  public static String require(String value) {
    if (value == null || !value.isEmpty() && !value.matches("[a-zA-Z0-9_.:-]{1,64}")) {
      throw new IllegalArgumentException("Invalid resource selector");
    }

    return value;
  }
}
