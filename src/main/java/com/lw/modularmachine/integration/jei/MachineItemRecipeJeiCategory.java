package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.client.preview.ClientMachineCatalog;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.client.recipe.MachineRecipeDisplay;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout;
import com.lw.modularmachine.integration.mekanism.jei.MekanismRecipeIngredients;
import com.lw.modularmachine.util.Mods;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

final class MachineItemRecipeJeiCategory implements IRecipeCategory<MachineRecipeJeiPage> {
  private final ResourceLocation machine;
  private final RecipeType<MachineRecipeJeiPage> type;
  private final MachineRecipeLayout layout;
  private final IIngredientType<FluidStack> fluidType;
  private final IDrawable icon, cell, shell, chemicalShell;

  @SuppressWarnings("unchecked")
  MachineItemRecipeJeiCategory(IJeiHelpers helpers, ResourceLocation machine, List<MachineRecipe> recipes) {
    this.machine = machine;
    type = new RecipeType<>(
        ResourceLocation.fromNamespaceAndPath("mmcece", "machine/" + machine.getNamespace() + "/" + machine.getPath()),
        MachineRecipeJeiPage.class);
    layout = new MachineRecipeLayout(recipes);
    var gui = helpers.getGuiHelper();
    fluidType = ((IPlatformFluidHelper<FluidStack>) helpers.getPlatformFluidHelper()).getFluidIngredientType();
    var registeredController = RegisteredMachineItems.controller(machine);
    icon = registeredController.isEmpty() ? gui.createBlankDrawable(16, 16) : gui
        .createDrawableIngredient(VanillaTypes.ITEM_STACK, registeredController);
    cell = gui.createDrawable(MachineRecipeDisplay.TEXTURE, 54, 0, 18, 18);
    shell = gui.createDrawable(MachineRecipeDisplay.TEXTURE, 0, 0, 18, 18);
    chemicalShell = gui.createDrawable(MachineRecipeDisplay.TEXTURE, 0, 18, 18, 18);
  }

  @Override
  public RecipeType<MachineRecipeJeiPage> getRecipeType() {
    return type;
  }
  @Override
  public Component getTitle() {
    return Component.translatable(ClientMachineCatalog.translation(machine));
  }
  @Override
  public IDrawable getIcon() {
    return icon;
  }
  @Override
  public int getWidth() {
    return layout.width;
  }
  @Override
  public int getHeight() {
    return layout.height;
  }

  @Override
  public void setRecipe(IRecipeLayoutBuilder builder, MachineRecipeJeiPage page, IFocusGroup focuses) {
    var recipe = page.recipes().getFirst().value();
    for (var part : layout.parts) {
      if (part.kind() == MachineRecipeLayout.Kind.CHEMICAL && Mods.MEKANISM.isLoaded()) {
        var slot = part.input() ? builder.addInputSlot() : builder.addOutputSlot();
        slot.setPosition(part.x() + 1, part.y() + 1).setBackground(cell, -1, -1).setOverlay(chemicalShell, -1, -1)
            .setHoverPadding(1, 1, 1, 1);
        var row = MachineRecipeDisplay.chemical(recipe, part);
        if (row != null) {
          MekanismRecipeIngredients.addChemical(slot, row);
          slot.addRichTooltipCallback(
              (view, tooltip) -> tooltip.addAll(MachineRecipeDisplay.chemicalTooltip(recipe, part)));
        }
      }
      if (part.kind() != MachineRecipeLayout.Kind.ITEM && part.kind() != MachineRecipeLayout.Kind.FLUID) {
        continue;
      }
      var slot = part.input() ? builder.addInputSlot() : builder.addOutputSlot();
      if (part.kind() == MachineRecipeLayout.Kind.ITEM) {
        slot.setPosition(part.x() + 1, part.y() + 1).setBackground(cell, -1, -1).setHoverPadding(1, 1, 1, 1);
        var items = part.input() ? recipe.inputs() : recipe.outputs();
        if (part.index() < items.size()) {
          var amount = items.get(part.index());
          slot.addItemStack(amount.ingredientStack()).setCustomRenderer(
              VanillaTypes.ITEM_STACK,
              new LegacyJeiItemRenderer(amount.amount(), amount.displayStack()));
          slot.addRichTooltipCallback(
              (view, tooltip) -> MachineRecipeDisplay.itemTooltip(amount, part.input()).forEach(tooltip::add));
        }
      } else {
        slot.setPosition(part.x(), part.y()).setBackground(cell, 0, 0).setOverlay(shell, 0, 0)
            .setFluidRenderer(1, false, 18, 18);
        var fluids = MachineRecipeDisplay.fluids(recipe, part.input());
        if (part.index() < fluids.size()) {
          var fluid = fluids.get(part.index());
          slot.addIngredient(fluidType, fluid);
          slot.addRichTooltipCallback(
              (view, tooltip) -> MachineRecipeDisplay.fluidTooltip(recipe, fluid, part.input(), part.index())
                  .forEach(tooltip::add));
        }
      }
    }
  }

  @Override
  public void draw(MachineRecipeJeiPage page, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
    var recipe = page.recipes().getFirst().value();
    MachineRecipeDisplay.draw(graphics, recipe, layout);
    if (!Mods.MEKANISM.isLoaded()) {
      for (var part : layout.parts) {
        if (part.kind() == MachineRecipeLayout.Kind.CHEMICAL) {
          MachineRecipeDisplay.drawChemical(graphics, recipe, part);
        }
      }
    }
  }
  @Override
  public void createRecipeExtras(IRecipeExtrasBuilder extras, MachineRecipeJeiPage page, IFocusGroup focuses) {
    var recipe = page.recipes().getFirst().value();
    extras.addTooltipArea(0, Math.max(0, layout.height - 9), layout.width, 9)
        .setTooltip(MachineRecipeDisplay.details(recipe));
    extras.addTooltipArea(layout.arrowX, layout.arrowY, 22, 15)
        .setTooltip(List.of(MachineRecipeDisplay.duration(recipe)));
    for (var part : layout.parts) {
      if (layout.hasResource(recipe, part)) {
        extras.addTooltipArea(part.x(), part.y(), part.kind().width, part.kind().height)
            .setTooltip(tooltip -> tooltip.addAll(MachineRecipeDisplay.resourceTooltip(recipe, layout, part)));
      } else if (part.kind() == MachineRecipeLayout.Kind.CHEMICAL
          && !Mods.MEKANISM.isLoaded()
          && MachineRecipeDisplay.chemical(recipe, part) != null) {
        extras.addTooltipArea(part.x(), part.y(), part.kind().width, part.kind().height)
            .setTooltip(MachineRecipeDisplay.chemicalTooltip(recipe, part));
      }
    }
  }

  @Override
  public ResourceLocation getRegistryName(MachineRecipeJeiPage page) {
    return page.recipes().getFirst().id();
  }
  @Override
  public List<ResourceLocation> getSourceRecipeIds(MachineRecipeJeiPage page) {
    return page.sourceIds();
  }
}
