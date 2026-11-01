package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.registry.MachineRegistries;
import java.util.LinkedHashMap;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Original 184x220 blueprint category used by the generic controllers: every
 * machine is a recipe, so the page indicator walks through all of them.
 * Dedicated controllers instead open their machine page, which embeds the same
 * preview on the left (see MachineItemRecipeJeiCategory).
 */
final class MachineStructureJeiCategory implements IRecipeCategory<ClientMachineJeiCatalog.Machine> {
  static final RecipeType<ClientMachineJeiCatalog.Machine> TYPE = new RecipeType<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "preview"),
      ClientMachineJeiCatalog.Machine.class);
  private final IDrawable background;
  MachineStructureJeiCategory(IJeiHelpers helpers) {
    background = helpers.getGuiHelper().createDrawable(
        ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/guiblueprint_new.png"),
        0,
        0,
        184,
        220);
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
    background.draw(graphics, 0, 0);
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
    var counts = new LinkedHashMap<List<Item>, Integer>();
    for (var part : machine.parts()) {
      var items = part.alternatives().stream().map(state -> state.getBlock().asItem()).filter(item -> item != Items.AIR)
          .distinct().toList();
      if (!items.isEmpty()) {
        counts.merge(items, 1, Integer::sum);
      }
    }
    int index = 0;
    for (var entry : counts.entrySet()) {
      var stacks = entry.getKey().stream().map(item -> new ItemStack(item, 1)).toList();
      builder.addInputSlot().setSlotName("material_" + index).addItemStacks(stacks)
          .setCustomRenderer(VanillaTypes.ITEM_STACK, new LegacyJeiItemRenderer(entry.getValue()))
          .setPosition(6 + index % 9 * 18, 180 + index / 9 * 18).addRichTooltipCallback(
              (view, tooltip) -> tooltip
                  .add(Component.translatable("jei.mmcece.machine_item.amount", entry.getValue())));
      index++;
    }
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
    var slots = builder.getRecipeSlots().getSlots().stream()
        .filter(slot -> slot.getSlotName().filter(name -> name.startsWith("material_")).isPresent()).toList();
    if (!slots.isEmpty()) {
      builder.addScrollGridWidget(slots, 9, 2).setPosition(5, 179);
    }
  }
}
