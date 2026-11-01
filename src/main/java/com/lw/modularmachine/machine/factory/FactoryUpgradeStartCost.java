package com.lw.modularmachine.machine.factory;

import com.google.gson.JsonElement;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

/** One whole-slot cost charged once for every run in a newly started batch. */
public record FactoryUpgradeStartCost(int durability, int consume) {
  public FactoryUpgradeStartCost {
    if (durability < 0
        || consume < 0
        || durability > 1_000_000
        || consume > 1_000_000
        || durability > 0 && consume > 0) {
      throw new IllegalArgumentException("Invalid upgrade start cost");
    }
  }

  public boolean present() {
    return durability > 0 || consume > 0;
  }

  public static FactoryUpgradeStartCost parse(JsonElement value, String path) {
    if (value == null) {
      return new FactoryUpgradeStartCost(0, 0);
    }
    if (!value.isJsonObject()) {
      throw new IllegalArgumentException(path + " must be an object");
    }
    var object = value.getAsJsonObject();
    if (!Set.of("durability", "consume").containsAll(object.keySet())) {
      throw new IllegalArgumentException(path + " has unknown fields");
    }

    return new FactoryUpgradeStartCost(
        parseAmount(object.get("durability"), path),
        parseAmount(object.get("consume"), path));
  }

  private static int parseAmount(JsonElement value, String path) {
    if (value == null) {
      return 0;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException(path + " must contain integer amounts");
    }

    return value.getAsBigDecimal().intValueExact();
  }

  public int maxRuns(ItemStack stack) {
    Objects.requireNonNull(stack, "stack");
    if (!present()) {
      return Integer.MAX_VALUE;
    }
    if (stack.isEmpty()) {
      return 0;
    }
    if (consume > 0) {
      return Math.max(0, stack.getCount() / consume);
    }
    if (stack.getCount() != 1 || !stack.isDamageableItem()) {
      return 0;
    }
    long remaining = (long) stack.getMaxDamage() - stack.getDamageValue();

    return remaining <= 0 ? 0 : Math.toIntExact((remaining + durability - 1) / durability);
  }

  public ItemStack apply(ItemStack stack, int runs) {
    if (runs < 1 || runs > maxRuns(stack)) {
      throw new IllegalArgumentException("Upgrade cannot pay requested start cost");
    }
    if (!present()) {
      return stack.copy();
    }
    if (consume > 0) {
      int remaining = stack.getCount() - Math.toIntExact((long) consume * runs);

      return remaining == 0 ? ItemStack.EMPTY : stack.copyWithCount(remaining);
    }
    long damage = stack.getDamageValue() + (long) durability * runs;
    if (damage >= stack.getMaxDamage()) {
      return ItemStack.EMPTY;
    }
    var replacement = stack.copy();
    replacement.setDamageValue(Math.toIntExact(damage));

    return replacement;
  }
}
