package com.lw.modularmachine.api.upgrade;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable script upgrade identity and display metadata, independent of item
 * bindings.
 */
public record UpgradeType(
    ResourceLocation id,
    String localizedName,
    float level,
    int maxStack,
    boolean dynamic,
    List<String> descriptions) {
  public UpgradeType {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(localizedName, "localizedName");
    descriptions = UpgradeDescriptions.validate(descriptions);
    if (id.toString().length() > 256 || localizedName.length() > 512 || !Float.isFinite(level) || maxStack < 1) {
      throw new IllegalArgumentException("Invalid script upgrade type");
    }
  }

  public String getName() {
    return id.toString();
  }
  public String getLocalizedName() {
    return localizedName;
  }
  public float getLevel() {
    return level;
  }
  public int getMaxStack() {
    return maxStack;
  }
}
