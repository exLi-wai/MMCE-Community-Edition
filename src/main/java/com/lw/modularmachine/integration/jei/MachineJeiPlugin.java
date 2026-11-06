package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

@JeiPlugin
public final class MachineJeiPlugin implements IModPlugin {
  private final Map<ResourceLocation, MachineItemRecipeJeiCategory> categories = new LinkedHashMap<>();
  private MachineStructureJeiCategory structureOverview;
  private Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> recipes = Map.of();
  static IJeiRuntime runtime;
  @Override
  public ResourceLocation getPluginUid() {
    return ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "jei");
  }

  @Override
  public void registerCategories(IRecipeCategoryRegistration registration) {
    categories.clear();
    var grouped = new LinkedHashMap<ResourceLocation, List<RecipeHolder<MachineRecipe>>>();
    var connection = Minecraft.getInstance().getConnection();
    if (connection != null) {
      connection.getRecipeManager().getAllRecipesFor(MachineRecipeRegistries.ITEM_TYPE.get()).stream()
          .sorted(Comparator.comparing(holder -> holder.id().toString())).forEach(
              holder -> grouped.computeIfAbsent(holder.value().machineId(), ignored -> new ArrayList<>()).add(holder));
    }
    recipes = grouped;
    var machines = new TreeSet<ResourceLocation>(grouped.keySet());
    machines.addAll(ClientMachineJeiCatalog.machines().keySet());
    structureOverview = new MachineStructureJeiCategory(registration.getJeiHelpers());
    registration.addRecipeCategories(structureOverview);
    for (var machine : machines) {
      var category = new MachineItemRecipeJeiCategory(
          registration.getJeiHelpers(),
          machine,
          grouped.getOrDefault(machine, List.of()).stream().map(RecipeHolder::value).toList());
      categories.put(machine, category);
      registration.addRecipeCategories(category);
    }
  }

  @Override
  public void registerRecipes(IRecipeRegistration registration) {
    for (var entry : categories.entrySet()) {
      registration.addRecipes(
          entry.getValue().getRecipeType(),
          recipes.getOrDefault(entry.getKey(), List.of()).stream()
              .map(holder -> new MachineRecipeJeiPage(entry.getKey(), List.of(holder))).toList());
    }
    if (structureOverview != null) {
      registration.addRecipes(
          structureOverview.getRecipeType(),
          ClientMachineJeiCatalog.machines().values().stream()
              .sorted(Comparator.comparing(ClientMachineJeiCatalog.Machine::id)).toList());
    }
  }

  @Override
  public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
    if (structureOverview != null) {
      registration.addRecipeCatalysts(
          structureOverview.getRecipeType(),
          MachineRegistries.CONTROLLER.get(),
          MachineRegistries.FACTORY_CONTROLLER.get());
    }
    for (var entry : categories.entrySet()) {
      var ordinary = controller(entry.getKey());
      var factory = factory(entry.getKey());
      var blueprint = blueprint(entry.getKey());
      for (var stack : List.of(ordinary, factory, blueprint)) {
        if (!stack.isEmpty()) {
          registration.addRecipeCatalyst(stack, entry.getValue().getRecipeType());
        }
      }
    }
  }

  static ItemStack controller(ResourceLocation machine) {
    return MachineRegistries.DEDICATED_CONTROLLERS.values().stream().map(holder -> holder.get())
        .filter(block -> block.dedicated().machine().equals(machine)).map(ItemStack::new).findFirst()
        .orElse(ItemStack.EMPTY);
  }

  static ItemStack factory(ResourceLocation machine) {
    return MachineRegistries.DEDICATED_FACTORIES.values().stream().map(holder -> holder.get())
        .filter(block -> block.dedicated().machine().equals(machine)).map(ItemStack::new).findFirst()
        .orElse(ItemStack.EMPTY);
  }

  static ItemStack blueprint(ResourceLocation machine) {
    var registered = MachineRegistries.BLUEPRINTS.get(machine);
    return registered == null ? ItemStack.EMPTY : registered.get().getDefaultInstance();
  }
  static void bookmark(ItemStack stack) {
    if (runtime != null) {
      runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack, false)
          .ifPresent(runtime.getBookmarkManager()::add);
    }
  }
  @Override
  public void onRuntimeAvailable(IJeiRuntime value) {
    runtime = value;
  }
  @Override
  public void onRuntimeUnavailable() {
    runtime = null;
  }
}
