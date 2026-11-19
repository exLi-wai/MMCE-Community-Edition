package com.lw.modularmachine.client.recipe;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.client.ChemicalDisplay;
import com.lw.modularmachine.util.minecraft.energy.EnergyDisplay;
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
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.client.preview.ClientMachineCatalog;
import java.util.IllegalFormatException;
import java.util.Locale;

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
      } else {
        var resource = layout.resource(recipe, part);
        if (resource != null) {
          resource.artwork().draw(graphics, part.x(), part.y(), tick);
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
              I18n.get("tooltip.machinery.energy.in") + I18n.get(
                  "tooltip.machinery.energy.tick",
                  EnergyDisplay.format(recipe.energyPerTick()),
                  EnergyDisplay.unitComponent().getString())));
      result.add(
          Component.translatable(
              "tooltip.machinery.energy.total",
              EnergyDisplay
                  .format(BigInteger.valueOf(recipe.energyPerTick()).multiply(BigInteger.valueOf(recipe.duration()))),
              EnergyDisplay.unitComponent()));
    }
    for (var requirement : recipe.interfaceInputs()) {
      var machine = ClientMachineCatalog.machines().get(recipe.machineId());
      var type = machine == null ? null : machine.interfaces().get(requirement.type());
      if (type != null && !type.jeiTooltip().isEmpty()) {
        Object[] arguments = switch (type.jeiTooltipArgsCount()) {
          case 0 -> new Object[0];
          case 1 -> new Object[]{requirement.minimum()};
          default -> new Object[]{requirement.minimum(), requirement.maximum()};
        };
        String template = I18n.get(type.jeiTooltip());
        try {
          result.add(Component.literal(String.format(Locale.ROOT, template, arguments)));
        } catch (IllegalFormatException invalid) {
          result.add(Component.literal(template));
        }
      } else {
        result.add(
            Component.translatable(
                "jei.mmcece.machine_item.interface_input",
                requirement.type(),
                requirement.minimum(),
                requirement.maximum()));
      }
    }
    return List.copyOf(result);
  }

  public static List<Component> resourceTooltip(MachineRecipe recipe, MachineRecipeLayout layout, MachineRecipeLayout.Part part) {
    var resource = layout.resource(recipe, part);
    return resource == null ? List.of() : resource.tooltip().get();
  }

  static int chemicalCount(MachineRecipe recipe, boolean input) {
    var chemicals = recipe.chemicals();
    return input ? chemicals.inputs().size() + chemicals.perTick().size() : chemicals.outputs().size() + chemicals
        .perTickOutputs().size();
  }

  public static ChemicalRequirement chemical(MachineRecipe recipe, MachineRecipeLayout.Part part) {
    var chemicals = recipe.chemicals();
    var batch = part.input() ? chemicals.inputs() : chemicals.outputs();
    if (part.index() < batch.size()) {
      return batch.get(part.index());
    }
    int index = part.index() - batch.size();
    var tick = part.input() ? chemicals.perTick() : chemicals.perTickOutputs();
    return index < tick.size() ? tick.get(index) : null;
  }

  public static List<Component> chemicalTooltip(MachineRecipe recipe, MachineRecipeLayout.Part part) {
    var row = chemical(recipe, part);
    boolean perTick = part
        .index() >= (part.input() ? recipe.chemicals().inputs().size() : recipe.chemicals().outputs().size());
    return row == null ? List.of() : chemicalTooltip(row, part.input(), perTick);
  }

  public static void drawChemical(GuiGraphics graphics, MachineRecipe recipe, MachineRecipeLayout.Part part) {
    int x = part.x();
    int y = part.y();
    graphics.blit(TEXTURE, x, y, 54, 0, 18, 18, 256, 256);
    var row = chemical(recipe, part);
    if (row != null) {
      var appearance = ChemicalDisplay.describe(new ChemicalStackData(row.chemical(), row.amount()));
      var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(appearance.texture());
      LegacyRecipeResources.tint(graphics, appearance.tint());
      graphics.blit(x, y, 0, 18, 18, sprite);
      graphics.setColor(1, 1, 1, 1);
    }
    graphics.blit(TEXTURE, x, y, 0, 18, 18, 18, 256, 256);
  }

  public static List<Component> itemTooltip(MachineRecipe.ItemAmount amount, boolean input) {
    return itemTooltip(amount, input, true);
  }

  public static List<Component> itemTooltipWithoutChance(MachineRecipe.ItemAmount amount) {
    return itemTooltip(amount, false, false);
  }

  private static List<Component> itemTooltip(MachineRecipe.ItemAmount amount, boolean input, boolean includeChance) {
    var result = new ArrayList<Component>();
    result.add(
        amount.minimumAmount() == amount.amount() ? Component
            .translatable("jei.mmcece.machine_item.amount", amount.amount()) : Component
                .translatable("jei.mmcece.machine_item.amount_range", amount.minimumAmount(), amount.amount()));
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
    (input ? recipe.fluidPerTickInputs() : recipe.fluidPerTickOutputs()).forEach(row -> result.add(row.stack()));
    result.addAll(input ? recipe.fluidInputs() : recipe.fluidOutputs());

    return List.copyOf(result);
  }

  public static List<Component> fluidTooltip(MachineRecipe recipe, FluidStack fluid, boolean input, int index) {
    var tickRows = input ? recipe.fluidPerTickInputs() : recipe.fluidPerTickOutputs();
    boolean perTick = index < tickRows.size();
    var row = perTick ? tickRows
        .get(index) : (input ? recipe.fluidInputRequirements() : recipe.fluidOutputRequirements())
            .get(index - tickRows.size());
    String selector;
    if (perTick) {
      selector = row.selector().isEmpty() && input ? recipe.selectors().fluidPerTick() : row.selector();
    } else {
      selector = input ? recipe.selectors().fluidInput() : recipe.selectors().fluidOutput();
    }
    var result = new ArrayList<Component>();
    result.add(
        Component.translatable(
            perTick ? "jei.mmcece.legacy.fluid_tick" : "jei.mmcece.machine_item.fluid_amount",
            fluid.getAmount()));
    result.add(selector(selector));
    if (row.chance() < 1) {
      var format = NumberFormat.getNumberInstance();
      format.setMaximumFractionDigits(2);
      String chance = row.chance() < .0001D ? "< 0.01%" : format.format(row.chance() * 100) + "%";
      String key = input ? row.chance() == 0 ? "tooltip.machinery.chance.in.never" : "tooltip.machinery.chance.in" : row
          .chance() == 0 ? "tooltip.machinery.chance.out.never" : "tooltip.machinery.chance.out";
      result.add(Component.translatable(key, chance));
    }
    if (!fluid.getComponentsPatch().isEmpty()) {
      result.add(
          Component.translatable("jei.mmcece.machine_item.fluid_components", fluid.getComponentsPatch().toString()));
    }

    return List.copyOf(result);
  }

  public static List<Component> details(MachineRecipe recipe) {
    var result = new ArrayList<Component>();
    result.add(Component.translatable("jei.mmcece.machine_item.priority", recipe.priority()));
    result.add(Component.translatable("jei.mmcece.machine_item.input_groups"));
    recipe.scriptOptions().tooltips().forEach(line -> result.add(Component.literal(line)));
    recipe.ingredientArrays().inputs().forEach(group -> groupDetails(result, group, false));
    recipe.ingredientArrays().randomOutputs().forEach(group -> groupDetails(result, group, true));
    for (var tool : recipe.itemPayments().tools()) {
      result.add(
          Component.translatable(
              "jei.mmcece.machine_item.durability",
              recipe.inputs().get(tool.row()).displayStack().getHoverName(),
              tool.durability()));
    }
    for (var fuel : recipe.itemPayments().fuels()) {
      result.add(Component.translatable("jei.mmcece.machine_item.fuel", fuel.burnTime()));
      result.add(selector(fuel.selector()));
    }
    for (var row : recipe.triggeredResources().rows()) {
      Component name = switch (row.resource()) {
        case "item" -> row.item().orElseThrow().getHoverName();
        case "fluid" -> row.fluid().orElseThrow().getHoverName();
        default -> ChemicalDisplay.describe(new ChemicalStackData(row.chemical().orElseThrow(), row.amount())).name();
      };
      result.add(
          Component.translatable(
              "jei.mmcece.machine_item.trigger",
              row.tick(),
              row.io() == RecipeModifier.Io.INPUT ? "→" : "←",
              name,
              row.amount()));
      if (row.repeatable()) {
        result.add(Component.translatable("jei.mmcece.machine_item.trigger_repeatable"));
      }
      result.add(selector(row.selector()));
    }
    return List.copyOf(result);
  }

  private static void groupDetails(List<Component> result, IngredientArrayData.Group group, boolean output) {
    String key = output ? "random_output" : group.optional() ? "catalyst" : "alternatives";
    result.add(Component.translatable("jei.mmcece.machine_item." + key));
    int shown = Math.min(8, group.alternatives().size());
    for (int index = 0; index < shown; index++) {
      var choice = group.alternatives().get(index);
      Component name = switch (choice.resource()) {
        case "item" -> choice.item().orElseThrow().getHoverName();
        case "fluid" -> choice.fluid().orElseThrow().getHoverName();
        default ->
          ChemicalDisplay.describe(new ChemicalStackData(choice.chemical().orElseThrow(), choice.amount())).name();
      };
      String amount = choice.minAmount() == choice.maxAmount() ? Long
          .toString(choice.amount()) : choice.minAmount() + ".." + choice.maxAmount();
      var line = Component.literal("  ").append(name).append(" ×" + amount);
      if (output) {
        line.append(Component.translatable("jei.mmcece.machine_item.weight", choice.chance()));
      }
      result.add(line);
    }
    if (shown < group.alternatives().size()) {
      result.add(
          Component.translatable("jei.mmcece.machine_item.more_alternatives", group.alternatives().size() - shown));
    }
    group.tooltips().forEach(line -> result.add(Component.literal(line)));
    result.add(selector(group.selector()));
  }

  private static List<Component> chemicalTooltip(ChemicalRequirement row, boolean input, boolean perTick) {
    var result = new ArrayList<Component>();
    var name = ChemicalDisplay.describe(new ChemicalStackData(row.chemical(), row.amount())).name();
    result.add(Component.literal(input ? "→ " : "← ").append(name).append(" ×" + row.amount() + (perTick ? "/t" : "")));
    result.add(selector(row.selector()));
    if (row.chance() < 1) {
      var format = NumberFormat.getNumberInstance();
      format.setMaximumFractionDigits(2);
      String chance = row.chance() < .0001D ? "< 0.01%" : format.format(row.chance() * 100) + "%";
      String key = input ? row.chance() == 0 ? "tooltip.machinery.chance.in.never" : "tooltip.machinery.chance.in" : row
          .chance() == 0 ? "tooltip.machinery.chance.out.never" : "tooltip.machinery.chance.out";
      result.add(Component.translatable(key, chance));
    }

    return List.copyOf(result);
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
