package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout;
import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.integration.jei.MachineJeiPlugin;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.registry.MachineRegistries;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.ItemStack;

/**
 * Optional EMI structure and machine-recipe integration. JEI remains complete.
 */
@EmiEntrypoint
public final class MachineEmiPlugin implements EmiPlugin {
  @Override
  public void register(EmiRegistry registry) {
    EmiPreviewInput.register();
    Map<ResourceLocation, MachineEmiCategory> recipeCategories = new LinkedHashMap<>();
    Map<ResourceLocation, MachineRecipeLayout> layouts = new LinkedHashMap<>();
    Map<ResourceLocation, ArrayList<RecipeHolder<MachineRecipe>>> recipes = new LinkedHashMap<>();
    registry.getRecipeManager().getAllRecipesFor(MachineRecipeRegistries.ITEM_TYPE.get()).stream()
        .sorted(Comparator.comparing(holder -> holder.id().toString())).forEach(
            holder -> recipes.computeIfAbsent(holder.value().machineId(), ignored -> new ArrayList<>()).add(holder));
    var machineIds = new TreeSet<ResourceLocation>(ClientMachineJeiCatalog.machines().keySet());
    machineIds.addAll(recipes.keySet());
    var displays = machineIds.stream().map(ClientMachineJeiCatalog.machines()::get).filter(Objects::nonNull).toList();
    MachineEmiCategory structure = displays.isEmpty() ? null : new MachineEmiCategory(
        ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "machines/structure"),
        EmiStack.of(MachineRegistries.CONTROLLER.get()),
        displays.getFirst(),
        true);
    if (structure != null) {
      registry.addCategory(structure);
      registry.addWorkstation(structure, EmiStack.of(MachineRegistries.CONTROLLER.get()));
      registry.addWorkstation(structure, EmiStack.of(MachineRegistries.FACTORY_CONTROLLER.get()));
    }
    for (var machine : machineIds) {
      var display = ClientMachineJeiCatalog.machines().get(machine);
      if (display == null) {
        continue;
      }
      var recipe = new MachineEmiCategory(categoryId(machine, "recipe"), icon(machine), display, false);
      layouts.put(
          machine,
          new MachineRecipeLayout(
              recipes.getOrDefault(machine, new ArrayList<>()).stream().map(holder -> holder.value()).toList()));
      recipeCategories.put(machine, recipe);
      registry.addCategory(recipe);
      addRecipeWorkstations(registry, recipe, machine);
    }
    registry.addDeferredRecipes(add -> {
      for (var machine : machineIds) {
        var display = ClientMachineJeiCatalog.machines().get(machine);
        if (display != null && structure != null) {
          add.accept(new MachineEmiRecipe(structure, display));
        }
        var recipe = recipeCategories.get(machine);
        if (recipe != null) {
          for (var holder : recipes.getOrDefault(machine, new ArrayList<>())) {
            add.accept(new MachineEmiItemRecipe(recipe, holder.id(), holder.value(), layouts.get(machine)));
          }
        }
      }
    });
  }

  private static ResourceLocation categoryId(ResourceLocation machine, String type) {
    return ResourceLocation.fromNamespaceAndPath(
        ModularMachine.MODID,
        "machine/" + machine.getNamespace() + "/" + machine.getPath() + "/" + type);
  }

  private static EmiStack icon(ResourceLocation machine) {
    var controller = MachineJeiPlugin.controller(machine);
    return controller.isEmpty() ? EmiStack.of(MachineRegistries.CONTROLLER.get()) : EmiStack.of(controller);
  }

  private static void addRecipeWorkstations(EmiRegistry registry, MachineEmiCategory recipe, ResourceLocation machine) {
    registry.addWorkstation(recipe, EmiStack.of(MachineRegistries.CONTROLLER.get()));
    registry.addWorkstation(recipe, EmiStack.of(MachineRegistries.FACTORY_CONTROLLER.get()));
    addWorkstation(registry, recipe, MachineJeiPlugin.controller(machine));
    addWorkstation(registry, recipe, MachineJeiPlugin.factory(machine));
    addWorkstation(registry, recipe, MachineJeiPlugin.blueprint(machine));
  }

  private static void addWorkstation(EmiRegistry registry, MachineEmiCategory category, ItemStack stack) {
    if (!stack.isEmpty()) {
      registry.addWorkstation(category, EmiStack.of(stack));
    }
  }
}
