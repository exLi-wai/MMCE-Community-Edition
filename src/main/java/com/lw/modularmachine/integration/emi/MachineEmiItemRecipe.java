package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.client.recipe.MachineRecipeDisplay;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.integration.mekanism.emi.MekanismEmiIngredients;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.util.Mods;
import dev.emi.emi.api.neoforge.NeoForgeEmiStack;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class MachineEmiItemRecipe extends BasicEmiRecipe {
  private final MachineRecipe recipe;
  private final MachineRecipeLayout layout;
  private final boolean hasExactChemicalCosts;

  MachineEmiItemRecipe(EmiRecipeCategory category, ResourceLocation id, MachineRecipe recipe, MachineRecipeLayout layout) {
    super(category, id, layout.width, layout.height);
    this.recipe = recipe;
    this.layout = layout;
    inputs = allInputs(recipe);
    outputs = allOutputs(recipe);
    long maxPerTick = Long.MAX_VALUE / recipe.duration();
    var chemicals = recipe.chemicals();
    int itemAndFluidCount = recipe.inputs().size() + recipe.outputs().size() + recipe.fluidInputRequirements()
        .size() + recipe.fluidOutputRequirements()
            .size() + recipe.fluidPerTickInputs().size() + recipe.fluidPerTickOutputs().size();
    int chemicalCount = chemicals.inputs().size() + chemicals.outputs().size() + chemicals.perTick().size() + chemicals
        .perTickOutputs().size();
    hasExactChemicalCosts = inputs.size() + outputs.size() == itemAndFluidCount + chemicalCount
        && chemicals.perTick().stream().noneMatch(row -> row.amount() > maxPerTick)
        && chemicals.perTickOutputs().stream().noneMatch(row -> row.amount() > maxPerTick);
  }

  @Override
  public boolean supportsRecipeTree() {
    return super.supportsRecipeTree() && hasExactChemicalCosts;
  }

  MachineRecipe definition() {
    return recipe;
  }

  @Override
  public void addWidgets(WidgetHolder widgets) {
    widgets.addDrawable(
        0,
        0,
        layout.width,
        layout.height,
        (graphics, mouseX, mouseY, delta) -> MachineRecipeDisplay.draw(graphics, recipe, layout));
    // Native slots remain last so tooltips never hide the hovered ingredient
    // from EMI's recipe/uses shortcuts on compact layouts.
    widgets.addTooltipText(MachineRecipeDisplay.details(recipe), 0, Math.max(0, layout.height - 9), layout.width, 9);
    widgets.addTooltipText(List.of(MachineRecipeDisplay.duration(recipe)), layout.arrowX, layout.arrowY, 22, 15);
    var fluidInputs = MachineRecipeDisplay.fluids(recipe, true);
    var fluidOutputs = MachineRecipeDisplay.fluids(recipe, false);
    for (var part : layout.parts) {
      switch (part.kind()) {
        case ITEM -> {
          var amounts = part.input() ? recipe.inputs() : recipe.outputs();
          var amount = amounts.get(part.index());
          addSlot(
              widgets,
              part,
              EmiStack.of(amount.ingredientStack(), amount.amount()),
              EmiStack.of(amount.displayStack(), amount.amount()),
              MachineRecipeDisplay.itemTooltip(amount, part.input()));
        }
        case FLUID -> {
          var fluid = (part.input() ? fluidInputs : fluidOutputs).get(part.index());
          var stack = NeoForgeEmiStack.of(fluid);
          addSlot(
              widgets,
              part,
              stack,
              stack,
              MachineRecipeDisplay.fluidTooltip(recipe, fluid, part.input(), part.index()));
        }
        case CHEMICAL -> {
          var stack = chemicalStack(MachineRecipeDisplay.chemical(recipe, part));
          var descriptions = MachineRecipeDisplay.chemicalTooltip(recipe, part);
          if (stack.isEmpty()) {
            widgets.addTooltipText(descriptions, part.x(), part.y(), part.kind().width, part.kind().height);
          } else {
            addSlot(widgets, part, stack, stack, descriptions);
          }
        }
        default -> {
          if (layout.hasResource(part)) {
            widgets.addTooltip(
                (mouseX, mouseY) -> MachineRecipeDisplay.resourceTooltip(recipe, layout, part).stream()
                    .map(line -> ClientTooltipComponent.create(line.getVisualOrderText())).toList(),
                part.x(),
                part.y(),
                part.kind().width,
                part.kind().height);
          }
        }
      }
    }
  }

  private void addSlot(WidgetHolder widgets, MachineRecipeLayout.Part part, EmiStack ingredient, EmiStack tooltipStack, List<Component> descriptions) {
    var slot = widgets.add(new RecipeSlot(part, ingredient, tooltipStack, descriptions));
    if (!part.input()) {
      slot.recipeContext(this);
    }
  }

  private static List<EmiIngredient> allInputs(MachineRecipe recipe) {
    var result = new ArrayList<EmiIngredient>();
    recipe.inputs().forEach(value -> result.add(item(value)));
    for (var row : recipe.fluidPerTickInputs()) {
      var perTick = row.stack();
      // Recipe-tree costs use the whole run; the slot still displays the per-tick
      // fee.
      result.add(
          NeoForgeEmiStack.of(perTick).setAmount((long) perTick.getAmount() * recipe.duration())
              .setChance((float) row.chance()));
    }
    recipe.fluidInputRequirements()
        .forEach(row -> result.add(NeoForgeEmiStack.of(row.stack()).setChance((float) row.chance())));
    recipe.chemicals().inputs().forEach(row -> addChemical(result, row, 1));
    recipe.chemicals().perTick().forEach(row -> addChemical(result, row, recipe.duration()));

    return List.copyOf(result);
  }

  private static List<EmiStack> allOutputs(MachineRecipe recipe) {
    var result = new ArrayList<EmiStack>();
    recipe.outputs().forEach(value -> result.add(item(value)));
    recipe.fluidOutputRequirements()
        .forEach(row -> result.add(NeoForgeEmiStack.of(row.stack()).setChance((float) row.chance())));
    for (var row : recipe.fluidPerTickOutputs()) {
      var perTick = row.stack();
      result.add(
          NeoForgeEmiStack.of(perTick).setAmount((long) perTick.getAmount() * recipe.duration())
              .setChance((float) row.chance()));
    }
    recipe.chemicals().outputs().forEach(row -> addChemical(result, row, 1));
    recipe.chemicals().perTickOutputs().forEach(row -> addChemical(result, row, recipe.duration()));

    return List.copyOf(result);
  }

  private static EmiStack item(MachineRecipe.ItemAmount amount) {
    return EmiStack.of(amount.ingredientStack(), amount.amount()).setChance((float) amount.chance());
  }

  private static void addChemical(List<? super EmiStack> result, ChemicalRequirement row, int ticks) {
    var stack = chemicalStack(row);
    if (!stack.isEmpty()) {
      // The ingredient remains searchable when the full run exceeds EMI's quantity
      // range.
      if (row.amount() <= Long.MAX_VALUE / ticks) {
        stack.setAmount(row.amount() * ticks);
      }
      stack.setChance((float) row.chance());
      result.add(stack);
    }
  }

  private static EmiStack chemicalStack(ChemicalRequirement row) {
    return Mods.MEKANISM.isLoaded() ? MekanismEmiIngredients.stack(row) : EmiStack.EMPTY;
  }

  /** The shared display paints the slot. EMI handles ingredient interactions. */
  private static final class RecipeSlot extends SlotWidget {
    private final EmiStack tooltipStack;

    RecipeSlot(MachineRecipeLayout.Part part, EmiStack ingredient, EmiStack tooltipStack, List<Component> descriptions) {
      super(ingredient, part.x(), part.y());
      this.tooltipStack = tooltipStack;
      drawBack(false);
      descriptions.forEach(this::appendTooltip);
    }

    @Override
    public void drawStack(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
      var result = new ArrayList<>(tooltipStack.getTooltip());
      addSlotTooltip(result);

      return result;
    }
  }
}
