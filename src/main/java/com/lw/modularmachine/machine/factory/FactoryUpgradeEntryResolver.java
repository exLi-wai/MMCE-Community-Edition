package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves explicit per-stack data before fixed item definitions; unknown items
 * are ignored.
 */
public final class FactoryUpgradeEntryResolver {
  private static final String MARKER = "mmcece:factory_upgrade";

  private FactoryUpgradeEntryResolver() {
  }

  public static Optional<FactoryUpgradeEntry> resolve(ItemStack stack) {
    return resolveMatched(stack, FactoryUpgradeService.snapshot().match(stack).orElse(null));
  }

  /**
   * Resolve values after the caller has selected the immutable stack definition.
   */
  public static Optional<FactoryUpgradeEntry> resolveMatched(ItemStack stack, FactoryUpgradeDefinition selected) {
    if (selected != null && selected.dynamicEffect() != null) {
      return Optional.empty();
    }

    return resolveBaseMatched(stack, selected);
  }

  public static Optional<FactoryUpgradeEntry> resolveForBus(ItemStack stack, FactoryUpgradeDefinition selected, ResourceLocation machine, BlockPos position, int slot, long epoch) {
    return resolveForBus(stack, selected, machine, position, slot, epoch, new CompoundTag());
  }

  public static Optional<FactoryUpgradeEntry> resolveForBus(ItemStack stack, FactoryUpgradeDefinition selected, ResourceLocation machine, BlockPos position, int slot, long epoch, CompoundTag busData) {
    if (selected != null && !selected.compatibility().allows(machine)) {
      return Optional.empty();
    }
    var base = resolveBaseMatched(stack, selected);
    if (base.isEmpty() || selected == null || selected.dynamicEffect() == null) {
      return base;
    }

    var resolved = DynamicUpgradeHandlers
        .evaluate(selected, stack, machine, position, slot, epoch, base.orElseThrow(), busData);
    var declared = base.orElseThrow().recipeModifiers();
    if (!resolved.recipeModifiers().isEmpty() && !resolved.recipeModifiers().equals(declared)) {
      throw new IllegalArgumentException("Dynamic handlers cannot replace declared recipe modifiers");
    }

    return Optional.of(resolved.withRecipeModifiers(declared));
  }

  private static Optional<FactoryUpgradeEntry> resolveBaseMatched(ItemStack stack, FactoryUpgradeDefinition selected) {
    if (stack == null || stack.isEmpty()) {
      return Optional.empty();
    }
    if (selected != null && !selected.item().equals(stack.getItem().builtInRegistryHolder().key().location())) {
      return Optional.empty();
    }
    if (!isMarked(stack)) {
      try {
        return selected == null ? Optional.empty() : Optional.of(selected.effect().multipliedBy(stack.getCount()));
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        return Optional.empty();
      }
    }
    if (selected == null
        && FactoryUpgradeService.snapshot().byItem()
            .containsKey(stack.getItem().builtInRegistryHolder().key().location())) {
      return Optional.empty();
    }
    var custom = stack.get(DataComponents.CUSTOM_DATA);
    var id = stack.getItem().builtInRegistryHolder().key().location();
    try {
      var data = custom.copyTag();
      var entry = new FactoryUpgradeEntry(
          id,
          intValue(data, "parallel_add", 0),
          intValue(data, "speed_numerator", 1),
          intValue(data, "speed_denominator", 1),
          intValue(data, "energy_numerator", 1),
          intValue(data, "energy_denominator", 1),
          intValue(data, "fluid_numerator", 1),
          intValue(data, "fluid_denominator", 1),
          selected == null ? List.of() : selected.effect().recipeModifiers());

      return Optional.of(entry.multipliedBy(stack.getCount()));
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return Optional.empty();
    }
  }

  public static boolean compatibleWith(ItemStack stack, ResourceLocation machine) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    var snapshot = FactoryUpgradeService.snapshot();
    var selected = snapshot.match(stack).orElse(null);

    return selected == null ? !snapshot.byItem().containsKey(
        stack.getItem().builtInRegistryHolder().key().location()) : selected.compatibility().allows(machine);
  }

  public static Optional<FactoryUpgradeEntry> resolve(ItemStack stack, ResourceLocation machine) {
    if (stack == null || stack.isEmpty()) {
      return Optional.empty();
    }
    var snapshot = FactoryUpgradeService.snapshot();
    var selected = snapshot.match(stack).orElse(null);
    if (selected != null && !selected.compatibility().allows(machine)
        || selected == null
            && snapshot.byItem().containsKey(stack.getItem().builtInRegistryHolder().key().location())) {
      return Optional.empty();
    }

    return resolveMatched(stack, selected);
  }

  public static boolean isMarked(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    var custom = stack.get(DataComponents.CUSTOM_DATA);

    return custom != null && custom.copyTag().getBoolean(MARKER);
  }

  public static boolean isUpgrade(ItemStack stack) {
    return stack != null
        && !stack.isEmpty()
        && (isMarked(stack) || FactoryUpgradeService.snapshot().match(stack).isPresent());
  }

  private static int intValue(CompoundTag data, String key, int fallback) {
    if (!data.contains(key)) {
      return fallback;
    }
    if (!data.contains(key, Tag.TAG_INT)) {
      throw new IllegalArgumentException("Upgrade field must be an integer: " + key);
    }

    return data.getInt(key);
  }
}
