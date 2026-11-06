package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
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
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

/** Adapter for MMCE CategoryDynamicRecipe / DynamicRecipeWrapper (GPL-3.0). */
final class MachineItemRecipeJeiCategory implements IRecipeCategory<MachineRecipeJeiPage> {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/jeirecipeicons_ce.png");
  private final ResourceLocation machine;
  private final RecipeType<MachineRecipeJeiPage> type;
  private final LegacyRecipeLayout layout;
  private final IIngredientType<FluidStack> fluidType;
  private final IDrawable icon, cell, shell, energyBack, energyFront, arrow;
  private final IDrawable[] frames = new IDrawable[23];

  @SuppressWarnings("unchecked")
  MachineItemRecipeJeiCategory(IJeiHelpers helpers, ResourceLocation machine, List<MachineRecipe> recipes) {
    this.machine = machine;
    type = new RecipeType<>(
        ResourceLocation.fromNamespaceAndPath("mmcece", "machine/" + machine.getNamespace() + "/" + machine.getPath()),
        MachineRecipeJeiPage.class);
    layout = new LegacyRecipeLayout(recipes);
    var gui = helpers.getGuiHelper();
    fluidType = ((IPlatformFluidHelper<FluidStack>) helpers.getPlatformFluidHelper()).getFluidIngredientType();
    var registeredController = MachineJeiPlugin.controller(machine);
    icon = registeredController.isEmpty() ? gui.createBlankDrawable(16, 16) : gui
        .createDrawableIngredient(VanillaTypes.ITEM_STACK, registeredController);
    cell = gui.createDrawable(TEXTURE, 54, 0, 18, 18);
    shell = gui.createDrawable(TEXTURE, 0, 0, 18, 18);
    energyBack = gui.createDrawable(TEXTURE, 36, 0, 18, 54);
    energyFront = gui.createDrawable(TEXTURE, 18, 0, 18, 54);
    arrow = gui.createDrawable(TEXTURE, 72, 0, 22, 15);
    for (int i = 1; i <= 22; i++) {
      frames[i] = gui.createDrawable(TEXTURE, 72, 15, i, 15);
    }
  }

  @Override
  public RecipeType<MachineRecipeJeiPage> getRecipeType() {
    return type;
  }
  @Override
  public Component getTitle() {
    return Component.translatable(ClientMachineJeiCatalog.translation(machine));
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
      if (part.kind() == LegacyRecipeLayout.Kind.ENERGY) {
        continue;
      }
      var slot = part.input() ? builder.addInputSlot() : builder.addOutputSlot();
      if (part.kind() == LegacyRecipeLayout.Kind.ITEM) {
        slot.setPosition(part.x() + 1, part.y() + 1).setBackground(cell, -1, -1).setHoverPadding(1, 1, 1, 1);
        var items = part.input() ? recipe.inputs() : recipe.outputs();
        if (part.index() < items.size()) {
          var amount = items.get(part.index());
          slot.addItemStack(amount.displayStack())
              .setCustomRenderer(VanillaTypes.ITEM_STACK, new LegacyJeiItemRenderer(amount.amount()));
          slot.addRichTooltipCallback((view, tooltip) -> {
            tooltip.add(Component.translatable("jei.mmcece.machine_item.amount", amount.amount()));
            if (amount.chance() < 1) {
              var format = NumberFormat.getNumberInstance();
              format.setMaximumFractionDigits(2);
              String chance = amount.chance() < .0001D ? "< 0.01%" : format.format(amount.chance() * 100) + "%";
              tooltip.add(
                  Component.translatable(
                      part.input() ? amount
                          .chance() == 0 ? "tooltip.machinery.chance.in.never" : "tooltip.machinery.chance.in" : amount
                              .chance() == 0 ? "tooltip.machinery.chance.out.never" : "tooltip.machinery.chance.out",
                      chance));
            }
            tooltip.add(selector(amount.selector()));
          });
        }
      } else {
        slot.setPosition(part.x(), part.y()).setBackground(cell, 0, 0).setOverlay(shell, 0, 0)
            .setFluidRenderer(1, false, 18, 18);
        var fluids = new ArrayList<FluidStack>();
        if (part.input() && !recipe.fluidPerTick().isEmpty()) {
          fluids.add(recipe.fluidPerTick());
        }
        fluids.addAll(part.input() ? recipe.fluidInputs() : recipe.fluidOutputs());
        if (part.index() < fluids.size()) {
          var fluid = fluids.get(part.index());
          boolean perTick = part.input() && part.index() == 0 && !recipe.fluidPerTick().isEmpty();
          String selector = perTick ? recipe.selectors()
              .fluidPerTick() : part.input() ? recipe.selectors().fluidInput() : recipe.selectors().fluidOutput();
          slot.addIngredient(fluidType, fluid);
          slot.addRichTooltipCallback((view, tooltip) -> {
            tooltip.add(
                Component.translatable(
                    perTick ? "jei.mmcece.legacy.fluid_tick" : "jei.mmcece.machine_item.fluid_amount",
                    fluid.getAmount()));
            tooltip.add(selector(selector));
            if (!fluid.getComponentsPatch().isEmpty()) {
              tooltip.add(
                  Component
                      .translatable("jei.mmcece.machine_item.fluid_components", fluid.getComponentsPatch().toString()));
            }
          });
        }
      }
    }
  }

  @Override
  public void draw(MachineRecipeJeiPage page, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
    var recipe = page.recipes().getFirst().value();
    arrow.draw(graphics, layout.arrowX, layout.arrowY);
    var mc = Minecraft.getInstance();
    double tick = Util.getMillis() / 50.0;
    int width = (int) Math.ceil((tick % recipe.duration()) / recipe.duration() * 22);
    if (width > 0) {
      frames[Math.min(22, width)].draw(graphics, layout.arrowX, layout.arrowY);
    }
    for (var part : layout.parts) {
      if (part.kind() == LegacyRecipeLayout.Kind.ENERGY) {
        energyBack.draw(graphics, part.x(), part.y());
        if (recipe.energyPerTick() > 0) {
          energyFront.draw(graphics, part.x(), part.y());
        }
      }
    }
    var text = text(recipe);
    int y = layout.height - text.size() * 9 - (recipe.energyPerTick() > 0 ? 2 : 0) - (recipe.interfaceInputs()
        .isEmpty() ? 0 : 2);
    for (int index = 0; index < text.size(); index++) {
      graphics.drawString(mc.font, text.get(index), 4, y, 0xFFFFFFFF, true);
      y += 9;
      if (index == 1 && recipe.energyPerTick() > 0) {
        y += 2;
      }
    }
  }

  static List<Component> text(MachineRecipe recipe) {
    var result = new ArrayList<Component>();
    if (recipe.energyPerTick() > 0) {
      result.add(
          Component.literal(
              I18n.get("tooltip.machinery.energy.in") + I18n
                  .get("tooltip.machinery.energy.tick", String.format("%,d", recipe.energyPerTick()), "FE")));
      result.add(
          Component.translatable(
              "tooltip.machinery.energy.total",
              String.format(
                  "%,d",
                  BigInteger.valueOf(recipe.energyPerTick()).multiply(BigInteger.valueOf(recipe.duration()))),
              "FE"));
    }
    for (var requirement : recipe.interfaceInputs()) {
      result.add(
          Component.translatable(
              "jei.mmcece.machine_item.interface_input",
              requirement.type(),
              requirement.minimum(),
              requirement.maximum()));
    }
    return List.copyOf(result);
  }
  @Override
  public void createRecipeExtras(IRecipeExtrasBuilder extras, MachineRecipeJeiPage page, IFocusGroup focuses) {
    var recipe = page.recipes().getFirst().value();
    int duration = recipe.duration();
    var details = new ArrayList<Component>();
    details.add(Component.translatable("jei.mmcece.machine_item.details", page.recipes().getFirst().id().toString()));
    details.add(Component.translatable("jei.mmcece.machine_item.priority", recipe.priority()));
    details.add(Component.translatable("jei.mmcece.machine_item.input_groups"));
    extras.addTooltipArea(0, Math.max(0, layout.height - 9), layout.width, 9).setTooltip(details);
    extras.addTooltipArea(layout.arrowX, layout.arrowY, 22, 15).setTooltip(
        List.of(
            Component.literal(
                I18n.get("tooltip.machinery.duration.seconds", duration / 20.0F) + I18n
                    .get("tooltip.machinery.duration.tick", duration))));
  }

  @Override
  public ResourceLocation getRegistryName(MachineRecipeJeiPage page) {
    return page.recipes().getFirst().id();
  }
  @Override
  public List<ResourceLocation> getSourceRecipeIds(MachineRecipeJeiPage page) {
    return page.sourceIds();
  }
  private static Component selector(String selector) {
    return Component.translatable("jei.mmcece.machine_item.selector", selector.isEmpty() ? "-" : selector);
  }
}
