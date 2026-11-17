package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable item rows; mutable stacks remain behind detached
 * accessors.
 */
@ApiStatus.Internal
public record ItemRecipeData(
    List<MachineRecipe.ItemAmount> inputs,
    List<MachineRecipe.ItemAmount> outputs,
    ItemPaymentData payments) {
  public static final ItemRecipeData NONE = new ItemRecipeData(List.of(), List.of());
  public static final MapCodec<ItemRecipeData> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          MachineRecipe.ItemAmount.CODEC.listOf().fieldOf("inputs").forGetter(ItemRecipeData::inputs),
          MachineRecipe.ItemAmount.CODEC.listOf().fieldOf("outputs").forGetter(ItemRecipeData::outputs),
          ItemPaymentData.CODEC.optionalFieldOf("item_payments", ItemPaymentData.NONE)
              .forGetter(ItemRecipeData::payments))
          .apply(instance, ItemRecipeData::new));

  public ItemRecipeData {
    inputs = List.copyOf(inputs);
    outputs = List.copyOf(outputs);
    if (inputs.size() > MachineRecipeJson.MAX_DEMANDS || outputs.size() > MachineRecipeJson.MAX_DEMANDS) {
      throw new IllegalArgumentException("Too many item requirements");
    }
    for (var tool : payments.tools()) {
      if (tool.row() >= inputs.size() || !inputs.get(tool.row()).ingredientStack().isDamageableItem()) {
        throw new IllegalArgumentException("Tool payments require a damageable input row");
      }
    }
  }

  public ItemRecipeData(List<MachineRecipe.ItemAmount> inputs, List<MachineRecipe.ItemAmount> outputs) {
    this(inputs, outputs, ItemPaymentData.NONE);
  }
}
