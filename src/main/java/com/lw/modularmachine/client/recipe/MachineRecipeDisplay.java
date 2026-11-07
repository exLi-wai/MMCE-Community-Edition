package com.lw.modularmachine.client.recipe;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Shared recipe artwork, quantities and descriptions for JEI and EMI. */
public final class MachineRecipeDisplay {
  public static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/jeirecipeicons_ce.png");

  private MachineRecipeDisplay() {
  }

  public static void draw(GuiGraphics graphics, MachineRecipe recipe, MachineRecipeLayout layout) {
    graphics.blit(TEXTURE, layout.arrowX, layout.arrowY, 72, 0, 22, 15, 256, 256);
    double tick = Util.getMillis() / 50.0;
    int width = (int) Math.ceil((tick % recipe.duration()) / recipe.duration() * 22);
    if (width > 0) {
      graphics.blit(TEXTURE, layout.arrowX, layout.arrowY, 72, 15, Math.min(22, width), 15, 256, 256);
    }
    for (var part : layout.parts) {
      if (part.kind() == MachineRecipeLayout.Kind.ENERGY) {
        graphics.blit(TEXTURE, part.x(), part.y(), 36, 0, 18, 54, 256, 256);
        if (recipe.energyPerTick() > 0) {
          graphics.blit(TEXTURE, part.x(), part.y(), 18, 0, 18, 54, 256, 256);
        }
      }
    }
    var text = text(recipe);
    int y = layout.height - text.size() * 9 - (recipe.energyPerTick() > 0 ? 2 : 0) - (recipe.interfaceInputs()
        .isEmpty() ? 0 : 2);
    for (int index = 0; index < text.size(); index++) {
      graphics.drawString(Minecraft.getInstance().font, text.get(index), 4, y, 0xFFFFFFFF, true);
      y += 9;
      if (index == 1 && recipe.energyPerTick() > 0) {
        y += 2;
      }
    }
  }

  public static List<Component> text(MachineRecipe recipe) {
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

  public static List<Component> itemTooltip(MachineRecipe.ItemAmount amount, boolean input) {
    return itemTooltip(amount, input, true);
  }

  public static List<Component> itemTooltipWithoutChance(MachineRecipe.ItemAmount amount) {
    return itemTooltip(amount, false, false);
  }

  private static List<Component> itemTooltip(MachineRecipe.ItemAmount amount, boolean input, boolean includeChance) {
    var result = new ArrayList<Component>();
    result.add(Component.translatable("jei.mmcece.machine_item.amount", amount.amount()));
    if (includeChance && amount.chance() < 1) {
      var format = NumberFormat.getNumberInstance();
      format.setMaximumFractionDigits(2);
      String chance = amount.chance() < .0001D ? "< 0.01%" : format.format(amount.chance() * 100) + "%";
      String key;
      if (input) {
        key = amount.chance() == 0 ? "tooltip.machinery.chance.in.never" : "tooltip.machinery.chance.in";
      } else {
        key = amount.chance() == 0 ? "tooltip.machinery.chance.out.never" : "tooltip.machinery.chance.out";
      }
      result.add(Component.translatable(key, chance));
    }
    result.add(selector(amount.selector()));

    return List.copyOf(result);
  }

  public static List<FluidStack> fluids(MachineRecipe recipe, boolean input) {
    var result = new ArrayList<FluidStack>();
    if (input && !recipe.fluidPerTick().isEmpty()) {
      result.add(recipe.fluidPerTick());
    }
    result.addAll(input ? recipe.fluidInputs() : recipe.fluidOutputs());

    return List.copyOf(result);
  }

  public static List<Component> fluidTooltip(MachineRecipe recipe, FluidStack fluid, boolean input, int index) {
    boolean perTick = input && index == 0 && !recipe.fluidPerTick().isEmpty();
    String selector;
    if (perTick) {
      selector = recipe.selectors().fluidPerTick();
    } else {
      selector = input ? recipe.selectors().fluidInput() : recipe.selectors().fluidOutput();
    }
    var result = new ArrayList<Component>();
    result.add(
        Component.translatable(
            perTick ? "jei.mmcece.legacy.fluid_tick" : "jei.mmcece.machine_item.fluid_amount",
            fluid.getAmount()));
    result.add(selector(selector));
    if (!fluid.getComponentsPatch().isEmpty()) {
      result.add(
          Component.translatable("jei.mmcece.machine_item.fluid_components", fluid.getComponentsPatch().toString()));
    }

    return List.copyOf(result);
  }

  public static List<Component> details(ResourceLocation id, MachineRecipe recipe) {
    return List.of(
        Component.translatable("jei.mmcece.machine_item.details", id.toString()),
        Component.translatable("jei.mmcece.machine_item.priority", recipe.priority()),
        Component.translatable("jei.mmcece.machine_item.input_groups"));
  }

  public static Component duration(MachineRecipe recipe) {
    return Component.literal(
        I18n.get("tooltip.machinery.duration.seconds", recipe.duration() / 20.0F) + I18n
            .get("tooltip.machinery.duration.tick", recipe.duration()));
  }

  private static Component selector(String selector) {
    return Component.translatable("jei.mmcece.machine_item.selector", selector.isEmpty() ? "-" : selector);
  }

  public static void drawItem(GuiGraphics graphics, ItemStack stack, long amount, int x, int y) {
    if (stack.isEmpty()) {
      return;
    }
    var font = Minecraft.getInstance().font;
    graphics.renderItem(stack, x, y);
    graphics.renderItemDecorations(font, stack, x, y, "");
    if (amount <= 1) {
      return;
    }
    String text = Screen.hasShiftDown() ? Long.toString(amount) : abbreviated(amount);
    float scale = amount >= 1000 ? .5F : 1F;
    graphics.pose().pushPose();
    try {
      graphics.pose().translate(x, y, 200);
      graphics.pose().scale(scale, scale, 1);
      graphics.drawString(
          font,
          text,
          (int) ((scale == .5F ? 16 : 17) / scale) - font.width(text),
          (int) ((scale == .5F ? 12 : 9) / scale),
          0xFFFFFFFF,
          true);
    } finally {
      graphics.pose().popPose();
    }
  }

  private static String abbreviated(long value) {
    String[] suffixes = {"", "K", "M", "G", "T", "P", "E"};
    long number = value;
    int tier = 0;
    while (number >= 1000 && tier < suffixes.length - 1) {
      number /= 1000;
      tier++;
    }

    return tier == 0 ? Long.toString(value) : Long.toString(number) + suffixes[tier];
  }
}
