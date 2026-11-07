package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class MachineEmiItemRecipe extends BasicEmiRecipe {
  private final MachineRecipe recipe;

  MachineEmiItemRecipe(EmiRecipeCategory category, ResourceLocation id, MachineRecipe recipe) {
    super(category, id, 176, 166);
    this.recipe = recipe;
    inputs = allInputs(recipe);
    outputs = allOutputs(recipe);
  }

  @Override
  public void addWidgets(WidgetHolder widgets) {
    int inputIndex = 0;
    for (var input : inputs) {
      widgets.addSlot(input, 4 + (inputIndex++ % 8) * 18, 8 + inputIndex / 8 * 20);
    }
    int outputIndex = 0;
    for (var output : outputs) {
      widgets.addSlot(output, 140 + (outputIndex++ % 2) * 18, 8 + outputIndex / 2 * 20).recipeContext(this);
    }
    widgets.addText(
        Component.translatable("jei.mmcece.machine_item.priority", recipe.priority()),
        4,
        132,
        0x404040,
        false);
    widgets
        .addText(Component.translatable("tooltip.machinery.duration.tick", recipe.duration()), 4, 146, 0x404040, false);
  }

  private static List<EmiIngredient> allInputs(MachineRecipe recipe) {
    var result = new ArrayList<EmiIngredient>();
    result.addAll(
        recipe.inputs().stream()
            .map(value -> EmiStack.of(value.displayStack(), value.amount()).setChance((float) value.chance()))
            .map(value -> (EmiIngredient) value).toList());
    if (!recipe.fluidPerTick().isEmpty()) {
      result.add(EmiStack.of(recipe.fluidPerTick().getFluid(), recipe.fluidPerTick().getAmount()));
    }
    recipe.fluidInputs().forEach(fluid -> result.add(EmiStack.of(fluid.getFluid(), fluid.getAmount())));
    return result;
  }

  private static List<EmiStack> allOutputs(MachineRecipe recipe) {
    var result = new ArrayList<EmiStack>();
    result.addAll(
        recipe.outputs().stream()
            .map(value -> EmiStack.of(value.displayStack(), value.amount()).setChance((float) value.chance()))
            .toList());
    recipe.fluidOutputs().forEach(fluid -> result.add(EmiStack.of(fluid.getFluid(), fluid.getAmount())));
    return result;
  }
}
