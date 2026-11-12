package com.lw.modularmachine.api.component;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable chemical identity and quantity, without a dependency on a chemical
 * platform.
 */
public record ChemicalStackData(ResourceLocation chemical, long amount) {
  private static final ResourceLocation EMPTY_ID = ResourceLocation.fromNamespaceAndPath("mmcece", "empty");
  public static final ChemicalStackData EMPTY = new ChemicalStackData(EMPTY_ID, 0);

  public ChemicalStackData {
    Objects.requireNonNull(chemical, "chemical");
    if (amount < 0 || amount > 0 && chemical.equals(EMPTY_ID)) {
      throw new IllegalArgumentException("Invalid chemical quantity or identity");
    }
    if (amount == 0) {
      chemical = EMPTY_ID;
    }
  }

  public boolean isEmpty() {
    return amount == 0;
  }

  public ChemicalStackData withAmount(long amount) {
    return amount == 0 ? EMPTY : new ChemicalStackData(chemical, amount);
  }

  public CompoundTag save() {
    var tag = new CompoundTag();
    if (!isEmpty()) {
      tag.putString("chemical", chemical.toString());
      tag.putLong("amount", amount);
    }

    return tag;
  }

  public static ChemicalStackData load(CompoundTag tag) {
    if (tag.isEmpty()) {
      return EMPTY;
    }
    if (!tag.contains("chemical", Tag.TAG_STRING)
        || !tag.contains("amount", Tag.TAG_LONG)
        || tag.getLong("amount") <= 0) {
      throw new IllegalArgumentException("Invalid saved chemical stack");
    }

    return new ChemicalStackData(ResourceLocation.parse(tag.getString("chemical")), tag.getLong("amount"));
  }
}
