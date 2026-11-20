package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.client.preview.ClientMachineCatalog;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.client.recipe.MachineRecipeDisplay;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout;
import com.lw.modularmachine.integration.mekanism.jei.MekanismRecipeIngredients;
import com.lw.modularmachine.util.Mods;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.List;
import java.util.Map;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

final class MachineItemRecipeJeiCategory implements IRecipeCategory<MachineRecipeJeiPage> {
  private final ResourceLocation machine;
  private final RecipeType<MachineRecipeJeiPage> type;
  private final Map<MachineRecipe, MachineRecipeLayout> layouts;
  private final int width, height;
  private final IIngredientType<FluidStack> fluidType;
  private final IIngredientRenderer<ItemStack> itemTooltip;
  private final IIngredientRenderer<FluidStack> fluidTooltip;
  private final IDrawable icon;

  @SuppressWarnings("unchecked")
  MachineItemRecipeJeiCategory(IJeiHelpers helpers, ResourceLocation machine, List<MachineRecipe> recipes) {
    this.machine = machine;
    type = new RecipeType<>(
        ResourceLocation.fromNamespaceAndPath("mmcece", "machine/" + machine.getNamespace() + "/" + machine.getPath()),
        MachineRecipeJeiPage.class);
    layouts = MachineRecipeLayout.perRecipe(recipes);
    width = layouts.values().stream().mapToInt(layout -> layout.width).max().orElse(34);
    height = layouts.values().stream().mapToInt(layout -> layout.height).max().orElse(18);
    var gui = helpers.getGuiHelper();
    fluidType = ((IPlatformFluidHelper<FluidStack>) helpers.getPlatformFluidHelper()).getFluidIngredientType();
    var ingredients = helpers.getIngredientManager();
    itemTooltip = ingredients.getIngredientRenderer(VanillaTypes.ITEM_STACK);
    fluidTooltip = new RecipeSlotTooltipRenderer<>(ingredients.getIngredientRenderer(fluidType), 18);
    var registeredController = RegisteredMachineItems.controller(machine);
    icon = registeredController.isEmpty() ? gui.createBlankDrawable(16, 16) : gui
        .createDrawableIngredient(VanillaTypes.ITEM_STACK, registeredController);
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
    return width;
  }
  @Override
  public int getHeight() {
    return height;
  }

  @Override
  public void setRecipe(IRecipeLayoutBuilder builder, MachineRecipeJeiPage page, IFocusGroup focuses) {
    var recipe = page.recipes().getFirst().value();
    var inputFluids = MachineRecipeDisplay.fluids(recipe, true);
    var outputFluids = MachineRecipeDisplay.fluids(recipe, false);
    for (var part : layouts.get(recipe).parts) {
      switch (part.kind()) {
        case ITEM -> addItem(ingredientSlot(builder, part), recipe, part);
        case FLUID -> addFluid(ingredientSlot(builder, part), recipe, part, part.input() ? inputFluids : outputFluids);
        case CHEMICAL -> addChemical(ingredientSlot(builder, part), recipe, part);
        default -> {
        }
      }
    }
  }

  private static IRecipeSlotBuilder ingredientSlot(IRecipeLayoutBuilder builder, MachineRecipeLayout.Part part) {
    return part.input() ? builder.addInputSlot() : builder.addOutputSlot();
  }

  private void addItem(IRecipeSlotBuilder slot, MachineRecipe recipe, MachineRecipeLayout.Part part) {
    var items = part.input() ? recipe.inputs() : recipe.outputs();
    var amount = items.get(part.index());
    var display = amount.displayStack();
    slot.setPosition(part.x() + 1, part.y() + 1).setHoverPadding(1, 1, 1, 1).addItemStack(amount.ingredientStack())
        .setCustomRenderer(VanillaTypes.ITEM_STACK, new RecipeSlotTooltipRenderer<>(itemTooltip, 16, stack -> display));
    slot.addRichTooltipCallback(
        (view, tooltip) -> tooltip.addAll(MachineRecipeDisplay.itemTooltip(amount, part.input())));
  }

  private void addFluid(IRecipeSlotBuilder slot, MachineRecipe recipe, MachineRecipeLayout.Part part, List<FluidStack> fluids) {
    var fluid = fluids.get(part.index());
    slot.setPosition(part.x(), part.y()).addIngredient(fluidType, fluid).setCustomRenderer(fluidType, fluidTooltip);
    slot.addRichTooltipCallback(
        (view, tooltip) -> tooltip
            .addAll(MachineRecipeDisplay.fluidTooltip(recipe, fluid, part.input(), part.index())));
  }

  private static void addChemical(IRecipeSlotBuilder slot, MachineRecipe recipe, MachineRecipeLayout.Part part) {
    slot.setPosition(part.x() + 1, part.y() + 1).setHoverPadding(1, 1, 1, 1);
    var row = MachineRecipeDisplay.chemical(recipe, part);
    if (row != null && Mods.MEKANISM.isLoaded()) {
      MekanismRecipeIngredients.addChemical(slot, row);
    }
    slot.addRichTooltipCallback((view, tooltip) -> tooltip.addAll(MachineRecipeDisplay.chemicalTooltip(recipe, part)));
  }

  @Override
  public void draw(MachineRecipeJeiPage page, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
    var recipe = page.recipes().getFirst().value();
    MachineRecipeDisplay.draw(graphics, recipe, layouts.get(recipe));
  }
  @Override
  public void createRecipeExtras(IRecipeExtrasBuilder extras, MachineRecipeJeiPage page, IFocusGroup focuses) {
    var recipe = page.recipes().getFirst().value();
    var layout = layouts.get(recipe);
    extras.addTooltipArea(0, Math.max(0, layout.height - 9), layout.width, 9)
        .setTooltip(MachineRecipeDisplay.details(recipe));
    extras.addTooltipArea(layout.arrowX, layout.arrowY, 22, 15)
        .setTooltip(List.of(MachineRecipeDisplay.duration(recipe)));
    for (var part : layout.parts) {
      if (layout.hasResource(part)) {
        extras.addTooltipArea(part.x(), part.y(), part.kind().width, part.kind().height)
            .setTooltip(tooltip -> tooltip.addAll(MachineRecipeDisplay.resourceTooltip(recipe, layout, part)));
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
