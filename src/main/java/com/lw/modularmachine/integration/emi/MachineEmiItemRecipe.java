package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.client.recipe.MachineRecipeDisplay;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import dev.emi.emi.api.neoforge.NeoForgeEmiStack;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Vector3f;

final class MachineEmiItemRecipe extends BasicEmiRecipe {
  private final MachineRecipe recipe;
  private final MachineRecipeLayout layout;

  MachineEmiItemRecipe(EmiRecipeCategory category, ResourceLocation id, MachineRecipe recipe, MachineRecipeLayout layout) {
    super(category, id, layout.width, layout.height);
    this.recipe = recipe;
    this.layout = layout;
    inputs = allInputs(recipe);
    outputs = allOutputs(recipe);
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
    for (var part : layout.parts) {
      SlotWidget slot;
      if (part.kind() == MachineRecipeLayout.Kind.ITEM) {
        var amounts = part.input() ? recipe.inputs() : recipe.outputs();
        slot = widgets.add(new ItemSlot(part, part.index() < amounts.size() ? amounts.get(part.index()) : null));
      } else if (part.kind() == MachineRecipeLayout.Kind.FLUID) {
        var fluids = MachineRecipeDisplay.fluids(recipe, part.input());
        var fluid = part.index() < fluids.size() ? fluids.get(part.index()) : FluidStack.EMPTY;
        slot = widgets.add(
            new FluidSlot(
                part,
                fluid,
                fluid.isEmpty() ? List.of() : MachineRecipeDisplay
                    .fluidTooltip(recipe, fluid, part.input(), part.index())));
      } else {
        continue;
      }
      if (!part.input() && !slot.getStack().isEmpty()) {
        slot.recipeContext(this);
      }
    }
  }

  private static List<EmiIngredient> allInputs(MachineRecipe recipe) {
    var result = new ArrayList<EmiIngredient>();
    recipe.inputs().forEach(value -> result.add(item(value)));
    var perTick = recipe.fluidPerTick();
    if (!perTick.isEmpty()) {
      // Recipe-tree costs use the whole run; the slot still displays the per-tick
      // fee.
      result.add(NeoForgeEmiStack.of(perTick).setAmount((long) perTick.getAmount() * recipe.duration()));
    }
    recipe.fluidInputs().forEach(fluid -> result.add(NeoForgeEmiStack.of(fluid)));

    return List.copyOf(result);
  }

  private static List<EmiStack> allOutputs(MachineRecipe recipe) {
    var result = new ArrayList<EmiStack>();
    recipe.outputs().forEach(value -> result.add(item(value)));
    recipe.fluidOutputs().forEach(fluid -> result.add(NeoForgeEmiStack.of(fluid)));

    return List.copyOf(result);
  }

  private static EmiStack item(MachineRecipe.ItemAmount amount) {
    return EmiStack.of(amount.displayStack(), amount.amount()).setChance((float) amount.chance());
  }

  private abstract static class RecipeSlot extends SlotWidget {
    RecipeSlot(EmiStack stack, MachineRecipeLayout.Part part, List<Component> descriptions) {
      super(stack, part.x(), part.y());
      descriptions.forEach(this::appendTooltip);
    }
    @Override
    public void drawBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.blit(MachineRecipeDisplay.TEXTURE, x, y, 54, 0, 18, 18, 256, 256);
    }

  }

  private static final class ItemSlot extends RecipeSlot {
    private final ItemStack display;
    private final long amount;

    ItemSlot(MachineRecipeLayout.Part part, MachineRecipe.ItemAmount requirement) {
      super(requirement == null ? EmiStack.EMPTY : item(requirement), part,
          requirement == null ? List.of() : MachineRecipeDisplay.itemTooltipWithoutChance(requirement));
      display = requirement == null ? ItemStack.EMPTY : requirement.displayStack();
      amount = requirement == null ? 0 : requirement.amount();
    }

    @Override
    public void drawStack(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      MachineRecipeDisplay.drawItem(graphics, display, amount, x + 1, y + 1);
    }
  }

  private static final class FluidSlot extends RecipeSlot {
    private final FluidStack fluid;

    FluidSlot(MachineRecipeLayout.Part part, FluidStack fluid, List<Component> descriptions) {
      super(fluid.isEmpty() ? EmiStack.EMPTY : NeoForgeEmiStack.of(fluid), part, descriptions);
      this.fluid = fluid.copy();
    }

    @Override
    public void drawStack(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (fluid.isEmpty()) {
        return;
      }
      var extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
      var texture = extensions.getStillTexture(fluid);
      if (texture == null) {
        return;
      }
      var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
      int color = extensions.getTintColor(fluid);
      int alpha = color >>> 24;
      float red = (color >> 16 & 255) / 255F;
      float green = (color >> 8 & 255) / 255F;
      float blue = (color & 255) / 255F;
      float opacity = alpha == 0 ? 1F : alpha / 255F;
      int tileWidth = sprite.contents().width();
      int tileHeight = sprite.contents().height();
      var pose = graphics.pose().last().pose();
      var topLeft = pose.transformPosition(new Vector3f(x, y, 0));
      var bottomRight = pose.transformPosition(new Vector3f(x + 18, y + 18, 0));
      graphics.flush();
      graphics.enableScissor(
          (int) Math.floor(Math.min(topLeft.x, bottomRight.x)),
          (int) Math.floor(Math.min(topLeft.y, bottomRight.y)),
          (int) Math.ceil(Math.max(topLeft.x, bottomRight.x)),
          (int) Math.ceil(Math.max(topLeft.y, bottomRight.y)));
      try {
        // Match JEI's full 18px tank and upward tiling; capacity is display-only.
        for (int dx = 0; dx < 18; dx += tileWidth) {
          for (int dy = 0; dy < 18; dy += tileHeight) {
            graphics
                .blit(x + dx, y + 18 - dy - tileHeight, 0, tileWidth, tileHeight, sprite, red, green, blue, opacity);
          }
        }
        graphics.flush();
      } finally {
        graphics.disableScissor();
      }
    }

    @Override
    public void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.blit(MachineRecipeDisplay.TEXTURE, x, y, 0, 0, 18, 18, 256, 256);
      super.drawOverlay(graphics, mouseX, mouseY, delta);
    }
  }
}
