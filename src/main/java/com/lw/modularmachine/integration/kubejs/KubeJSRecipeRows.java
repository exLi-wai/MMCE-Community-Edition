package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mutable rows owned by one recipe builder, detached from the script-provided
 * stacks.
 */
@ApiStatus.Internal
final class KubeJSRecipeRows {
  private KubeJSRecipeRows() {
  }

  static final class ItemRow {
    final ItemStack prototype;
    final long amount;
    double chance = 1;
    boolean parallelUnaffected;
    String selector = "";
    String match = "exact";
    ItemStack preview;
    ResourceLocation checker;
    RecipeItemCallbacks.Checker inlineChecker;
    final List<ResourceLocation> itemModifiers = new ArrayList<>();
    final Map<ResourceLocation, RecipeItemCallbacks.Modifier> inlineModifiers = new LinkedHashMap<>();
    final Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inlineInputModifiers = new LinkedHashMap<>();

    ItemRow(ItemStack prototype, long amount) {
      this.prototype = prototype;
      this.amount = amount;
    }
  }

  static final class ChemicalRow {
    final ResourceLocation chemical;
    final long amount;
    String selector = "";

    ChemicalRow(ResourceLocation chemical, long amount) {
      this.chemical = chemical;
      this.amount = amount;
    }

    ChemicalRequirement requirement() {
      return new ChemicalRequirement(chemical, amount, selector);
    }
  }

  static final class FluidRow {
    final FluidStack stack;
    double chance = 1;
    boolean parallelUnaffected;

    FluidRow(FluidStack stack) {
      this.stack = stack;
    }

    FluidRequirement requirement() {
      return new FluidRequirement(stack, chance, parallelUnaffected);
    }
  }

}
