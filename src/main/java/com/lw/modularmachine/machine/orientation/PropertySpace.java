package com.lw.modularmachine.machine.orientation;

/** Coordinate space applies to all explicit state constraints on a part. */
public enum PropertySpace {
  LOCAL("local"),
  WORLD("world"),
  LEGACY("legacy");

  private final String serializedName;

  PropertySpace(String serializedName) {
    this.serializedName = serializedName;
  }

  public String serializedName() {
    return serializedName;
  }

  public static PropertySpace parse(String value) {
    for (PropertySpace space : values()) {
      if (space.serializedName.equals(value)) {
        return space;
      }
    }
    throw new IllegalArgumentException("Unknown properties space: " + value);
  }
}
