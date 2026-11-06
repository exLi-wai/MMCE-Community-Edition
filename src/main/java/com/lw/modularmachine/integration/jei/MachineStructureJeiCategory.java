package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class MachineStructureJeiCategory implements IRecipeCategory<ClientMachineJeiCatalog.Machine> {
  static final RecipeType<ClientMachineJeiCatalog.Machine> TYPE = new RecipeType<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "preview"),
      ClientMachineJeiCatalog.Machine.class);
  MachineStructureJeiCategory(IJeiHelpers helpers) {
  }
  @Override
  public RecipeType<ClientMachineJeiCatalog.Machine> getRecipeType() {
    return TYPE;
  }
  @Override
  public Component getTitle() {
    return Component.translatable("jei.mmcece.legacy.preview");
  }
  @Override
  public IDrawable getIcon() {
    return null;
  }
  @Override
  public int getWidth() {
    return 184;
  }
  @Override
  public int getHeight() {
    return 220;
  }
  @Override
  public boolean needsRecipeBorder() {
    return false;
  }
  @Override
  public ResourceLocation getRegistryName(ClientMachineJeiCatalog.Machine machine) {
    return ResourceLocation.fromNamespaceAndPath(machine.id().getNamespace(), "/structure/" + machine.id().getPath());
  }
  @Override
  public List<ResourceLocation> getSourceRecipeIds(ClientMachineJeiCatalog.Machine machine) {
    return List.of();
  }
  @Override
  public void draw(ClientMachineJeiCatalog.Machine machine, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
    // The structure view intentionally uses the JEI page background directly.
  }
  @Override
  public void setRecipe(IRecipeLayoutBuilder builder, ClientMachineJeiCatalog.Machine machine, IFocusGroup focuses) {
    // Focus ingredients drive "uses" lookups: the generic controllers are part
    // of every preview (so their lookup pages through all machines), while a
    // dedicated controller/blueprint only matches its own machine.
    focus(builder, new ItemStack(MachineRegistries.CONTROLLER.get().asItem()));
    focus(builder, new ItemStack(MachineRegistries.FACTORY_CONTROLLER.get().asItem()));
    focus(builder, MachineJeiPlugin.controller(machine.id()));
    focus(builder, MachineJeiPlugin.factory(machine.id()));
    focus(builder, MachineJeiPlugin.blueprint(machine.id()));
    focus(builder, MachineJeiPlugin.blueprint(machine.id()), RecipeIngredientRole.OUTPUT);
  }
  private static void focus(IRecipeLayoutBuilder builder, ItemStack stack) {
    focus(builder, stack, RecipeIngredientRole.INPUT);
  }

  private static void focus(IRecipeLayoutBuilder builder, ItemStack stack, RecipeIngredientRole role) {
    if (!stack.isEmpty()) {
      builder.addInvisibleIngredients(role).addItemStack(stack);
    }
  }

  @Override
  public void createRecipeExtras(IRecipeExtrasBuilder builder, ClientMachineJeiCatalog.Machine machine, IFocusGroup focuses) {
    var preview = new LegacyStructureJeiWidget(machine);
    builder.addWidget(preview);
    builder.addGuiEventListener(preview);
  }
}
