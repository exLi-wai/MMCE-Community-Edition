package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict bounded NBT codec for a published factory upgrade snapshot. */
public final class FactoryUpgradeSnapshotCodec {
  private FactoryUpgradeSnapshotCodec() {
  }

  public static CompoundTag write(FactoryUpgradeSnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("Missing factory upgrade snapshot");
    }
    var tag = new CompoundTag();
    tag.putLong("revision", snapshot.revision());
    tag.putInt("parallel_limit", snapshot.parallelLimit());
    tag.putInt("speed_num", snapshot.speedNumerator());
    tag.putInt("speed_den", snapshot.speedDenominator());
    tag.putInt("energy_num", snapshot.energyNumerator());
    tag.putInt("energy_den", snapshot.energyDenominator());
    tag.putInt("fluid_num", snapshot.fluidNumerator());
    tag.putInt("fluid_den", snapshot.fluidDenominator());
    var rules = new ListTag();
    for (var rule : snapshot.recipeModifiers()) {
      var saved = new CompoundTag();
      saved.putString("target", rule.target().serialized());
      saved.putString("io", rule.io().serialized());
      saved.putString("operation", rule.operation().serialized());
      saved.putDouble("value", rule.value());
      saved.putBoolean("affect_chance", rule.affectChance());
      rules.add(saved);
    }
    tag.put("recipe_modifiers", rules);
    return tag;
  }

  public static FactoryUpgradeSnapshot read(CompoundTag tag) {
    if (tag == null
        || !tag.contains("revision", Tag.TAG_LONG)
        || !tag.contains("parallel_limit", Tag.TAG_INT)
        || !tag.contains("speed_num", Tag.TAG_INT)
        || !tag.contains("speed_den", Tag.TAG_INT)
        || !tag.contains("energy_num", Tag.TAG_INT)
        || !tag.contains("energy_den", Tag.TAG_INT)
        || !tag.contains("fluid_num", Tag.TAG_INT)
        || !tag.contains("fluid_den", Tag.TAG_INT)
        || !tag.contains("recipe_modifiers", Tag.TAG_LIST)) {
      throw new IllegalArgumentException("Incomplete factory upgrade snapshot");
    }
    var savedRules = (ListTag) tag.get("recipe_modifiers");
    if (savedRules.size() > RecipeModifiers.MAX_RULES
        || !savedRules.isEmpty() && savedRules.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid saved recipe modifier list");
    }
    var rules = new ArrayList<RecipeModifier>();
    for (var element : savedRules) {
      var rule = (CompoundTag) element;
      if (!rule.getAllKeys().equals(Set.of("target", "io", "operation", "value", "affect_chance"))
          || !rule.contains("target", Tag.TAG_STRING)
          || !rule.contains("io", Tag.TAG_STRING)
          || !rule.contains("operation", Tag.TAG_STRING)
          || !rule.contains("value", Tag.TAG_DOUBLE)
          || !rule.contains("affect_chance", Tag.TAG_BYTE)
          || rule.getByte("affect_chance") < 0
          || rule.getByte("affect_chance") > 1) {
        throw new IllegalArgumentException("Invalid saved recipe modifier");
      }
      rules.add(
          new RecipeModifier(
              RecipeModifier.Target.parse(rule.getString("target")),
              RecipeModifier.Io.parse(rule.getString("io")),
              RecipeModifier.Operation.parse(rule.getString("operation")),
              rule.getDouble("value"),
              rule.getBoolean("affect_chance")));
    }

    return new FactoryUpgradeSnapshot(
        tag.getLong("revision"),
        tag.getInt("parallel_limit"),
        tag.getInt("speed_num"),
        tag.getInt("speed_den"),
        tag.getInt("energy_num"),
        tag.getInt("energy_den"),
        tag.getInt("fluid_num"),
        tag.getInt("fluid_den"),
        rules);
  }
}
