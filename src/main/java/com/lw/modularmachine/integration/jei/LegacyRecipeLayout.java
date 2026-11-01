package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.machine.recipe.MachineItemRecipe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Geometry ported from MMCE CategoryDynamicRecipe / RecipeLayoutPart (GPL-3.0).
 */
final class LegacyRecipeLayout {
  enum Kind {
    ENERGY,
    FLUID,
    ITEM
  }
  record Part(Kind kind, boolean input, int index, int x, int y) {
  }
  final List<Part> parts;
  final int width, height, arrowX, arrowY;

  LegacyRecipeLayout(List<MachineItemRecipe> recipes) {
    var result = new ArrayList<Part>();
    int x = 4, highest = 0, arrow = 8;
    for (boolean input : new boolean[]{true, false}) {
      for (Kind kind : input ? new Kind[]{Kind.ENERGY, Kind.FLUID, Kind.ITEM} : new Kind[]{Kind.ITEM, Kind.FLUID,
          Kind.ENERGY}) {
        int count = recipes.stream().mapToInt(recipe -> count(recipe, kind, input)).max().orElse(0);
        int columns = kind == Kind.ITEM ? itemColumns(count) : kind == Kind.FLUID ? Math.max((count + 3) / 4, 1) : 1;
        int start = x;
        for (int i = 0; i < count; i++) {
          int px = start + i % columns * 18;
          int py = i / columns * (kind == Kind.ENERGY ? 63 : 18);
          result.add(new Part(kind, input, i, px, py));
          x = Math.max(x, px + 18);
          highest = Math.max(highest, py + (kind == Kind.ENERGY ? 63 : 18));
        }
      }
      if (input) {
        x += 4;
        arrow = x;
        x += 26;
      }
    }
    parts = List.copyOf(result);
    arrowX = arrow;
    arrowY = highest / 2 / 2;
    int textWidth = recipes.stream().flatMap(recipe -> MachineItemRecipeJeiCategory.text(recipe).stream())
        .mapToInt(Minecraft.getInstance().font::width).max().orElse(0);
    width = Math.max(x, textWidth + 4);
    height = Math.max(
        18,
        highest + recipes.stream()
            .mapToInt(
                recipe -> (recipe.energyPerTick() > 0 ? 20 : 0) + recipe.interfaceInputs().size() * 9 + (recipe
                    .interfaceInputs().isEmpty() ? 0 : 2))
            .max().orElse(0));
  }

  static int count(MachineItemRecipe recipe, Kind kind, boolean input) {
    return switch (kind) {
      case ENERGY -> input && recipe.energyPerTick() > 0 ? 1 : 0;
      case FLUID ->
        input ? recipe.fluidInputs().size() + (recipe.fluidPerTick().isEmpty() ? 0 : 1) : recipe.fluidOutputs().size();
      case ITEM -> input ? recipe.inputs().size() : recipe.outputs().size();
    };
  }

  static int itemColumns(int count) {
    if (count <= 3) {
      return 1;
    }
    if (count == 4) {
      return 2;
    }
    if (count <= 9) {
      return 3;
    }
    if (count == 12) {
      return 4;
    }
    int sqrt = (int) Math.round(Math.sqrt(count));
    if (count % sqrt == 0) {
      return sqrt;
    }
    int range = sqrt <= 3 ? sqrt : sqrt / 2;
    for (int i = 1; i < range; i++) {
      if (count % (sqrt + i) == 0) {
        return sqrt + i;
      }
      if (count % (sqrt - i) == 0) {
        return sqrt - i;
      }
    }
    return sqrt;
  }
}
