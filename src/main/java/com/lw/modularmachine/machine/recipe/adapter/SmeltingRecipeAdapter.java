package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.adapter.MachineRecipeAdapter;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import org.jetbrains.annotations.ApiStatus;

/** Internal conversion of ordinary item-only furnace ingredients. */
@ApiStatus.Internal
public final class SmeltingRecipeAdapter implements MachineRecipeAdapter {
  public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("minecraft", "furnace");
  private static final int MAX_INGREDIENT_DEPTH = 64;

  @Override
  public List<MachineRecipeBuilder> adapt(RecipeAdapterContext context) {
    var result = new ArrayList<MachineRecipeBuilder>();
    for (var source : context.sources()) {
      if (source.value().getType() != RecipeType.SMELTING) {
        if (!context.settings().allRecipes()) {
          throw new IllegalArgumentException(source.id() + ": Source is not a furnace smelting recipe");
        }
        continue;
      }
      if (source.value().getClass() != SmeltingRecipe.class) {
        throw new IllegalArgumentException(source.id() + ": Custom smelting implementations require their own adapter");
      }
      var recipe = (SmeltingRecipe) source.value();
      var ingredients = recipe.getIngredients();
      if (ingredients.size() != 1) {
        throw new IllegalArgumentException(
            source.id() + ": Smelting adapter requires one ordinary item/tag ingredient");
      }
      var ingredient = ingredients.getFirst();
      var candidates = new TreeMap<ResourceLocation, ItemStack>();
      collect(ingredient, context, source.id(), candidates, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
      var output = recipe.getResultItem(context.registries());
      if (output.isEmpty()) {
        throw new IllegalArgumentException(source.id() + ": Smelting output must not be empty");
      }
      for (var candidate : candidates.entrySet()) {
        result.add(
            context.builder(source.id(), candidate.getKey().toString(), recipe.getCookingTime())
                .itemInputIgnoringComponents(candidate.getValue(), 1)
                .itemOutput(output.copyWithCount(1), output.getCount()));
      }
    }
    return List.copyOf(result);
  }

  private static void collect(Ingredient ingredient, RecipeAdapterContext context, ResourceLocation source, TreeMap<ResourceLocation, ItemStack> candidates, Set<Ingredient> active, int depth) {
    if (depth > MAX_INGREDIENT_DEPTH || !active.add(ingredient)) {
      throw new IllegalArgumentException(source + ": Smelting ingredient is cyclic or too deeply nested");
    }
    try {
      if (ingredient.getCustomIngredient() instanceof CompoundIngredient compound) {
        for (var child : compound.children()) {
          collect(child, context, source, candidates, active, depth + 1);
        }
        return;
      }
      if (ingredient.isCustom()) {
        throw new IllegalArgumentException(source + ": Custom smelting ingredient requires its own adapter");
      }
      for (var value : ingredient.getValues()) {
        if (value instanceof Ingredient.ItemValue item) {
          var stack = item.item();
          if (stack.isEmpty()) {
            throw new IllegalArgumentException(source + ": Ingredient exposes an empty item");
          }
          addCandidate(candidates, stack);
        } else if (value instanceof Ingredient.TagValue tag) {
          for (var item : context.tags().items(tag.tag().location())) {
            addCandidate(candidates, new ItemStack(item));
          }
        } else {
          throw new IllegalArgumentException(source + ": Ingredient value requires its own adapter");
        }
      }
    } finally {
      active.remove(ingredient);
    }
  }

  private static void addCandidate(TreeMap<ResourceLocation, ItemStack> candidates, ItemStack stack) {
    var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    candidates.putIfAbsent(id, stack.copyWithCount(1));
  }
}
