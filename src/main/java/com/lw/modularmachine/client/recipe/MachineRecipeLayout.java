package com.lw.modularmachine.client.recipe;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

public final class MachineRecipeLayout {
  public enum Kind {
    ENERGY(18, 63, 0),
    MANA(5, 63, 4),
    EMBER(5, 102, 0),
    FLUID(18, 18, 0),
    CHEMICAL(18, 18, 0),
    AIR(50, 50, 8),
    ENVIRONMENT(16, 16, 4),
    ITEM(18, 18, 0),
    LASER(16, 16, 0),
    HEAT(16, 16, 0),
    AURA(16, 16, 0),
    WILL(16, 16, 0),
    VIS(16, 16, 0),
    FLUX(16, 16, 0),
    LIFE_ESSENCE(18, 18, 0),
    METEOR(16, 16, 0),
    ESSENTIA(16, 16, 0),
    STARLIGHT(16, 16, 0),
    CONSTELLATION(58, 58, 0);

    public final int width, height;
    final int gap;

    Kind(int width, int height, int gap) {
      this.width = width;
      this.height = height;
      this.gap = gap;
    }
  }
  public record Part(Kind kind, boolean input, int index, int x, int y) {
  }
  private final Map<MachineRecipe, LegacyRecipeResources> resources = new IdentityHashMap<>();
  public final List<Part> parts;
  public final int width, height, arrowX, arrowY;

  public MachineRecipeLayout(List<MachineRecipe> recipes) {
    var result = new ArrayList<Part>();
    recipes.forEach(recipe -> resources.put(recipe, new LegacyRecipeResources(recipe)));
    int x = 4, highest = 0, arrow = 8;
    var kinds = List.of(Kind.values());
    for (boolean input : new boolean[]{true, false}) {
      for (Kind kind : input ? kinds : kinds.reversed()) {
        int count = recipes.stream().mapToInt(recipe -> count(recipe, kind, input)).max().orElse(0);
        int columns = switch (kind) {
          case ITEM -> itemColumns(count);
          case FLUID, CHEMICAL, ESSENTIA, STARLIGHT -> Math.max((count + 3) / 4, 1);
          case ENVIRONMENT -> 3;
          case CONSTELLATION -> Math.max((count + 1) / 2, 1);
          default -> 1;
        };
        int start = x;
        for (int i = 0; i < count; i++) {
          int px = start + i % columns * (kind.width + kind.gap);
          int py = i / columns * (kind.height + kind.gap);
          result.add(new Part(kind, input, i, px, py));
          x = Math.max(x, px + kind.width + kind.gap);
          highest = Math.max(highest, py + kind.height);
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
    int textWidth = recipes.stream().flatMap(recipe -> MachineRecipeDisplay.text(recipe).stream())
        .mapToInt(Minecraft.getInstance().font::width).max().orElse(0);
    width = Math.max(x, textWidth + 4);
    boolean hasAdditional = result.stream().anyMatch(part -> switch (part.kind()) {
      case ENERGY, FLUID, ITEM -> false;
      default -> true;
    });
    int textHeight = recipes.stream()
        .mapToInt(
            recipe -> (recipe.energyPerTick() > 0 ? 2 : 0) + MachineRecipeDisplay.text(recipe).size() * 9 + (recipe
                .interfaceInputs().isEmpty() ? 0 : 2))
        .max().orElse(0);
    height = Math
        .max(18, Math.max(highest, hasAdditional ? arrowY + 15 : 0) + Math.max(textHeight, hasAdditional ? 9 : 0));
  }

  private int count(MachineRecipe recipe, Kind kind, boolean input) {
    return switch (kind) {
      case ENERGY -> input && recipe.energyPerTick() > 0 ? 1 : 0;
      case FLUID -> input ? recipe.fluidInputs().size() + recipe.fluidPerTickInputs().size() : recipe.fluidOutputs()
          .size() + recipe.fluidPerTickOutputs().size();
      case ITEM -> input ? recipe.inputs().size() : recipe.outputs().size();
      case CHEMICAL -> MachineRecipeDisplay.chemicalCount(recipe, input);
      default -> resources.get(recipe).count(kind, input);
    };
  }

  LegacyRecipeResources.Entry resource(MachineRecipe recipe, Part part) {
    return resources.get(recipe).entry(part);
  }

  public boolean hasResource(MachineRecipe recipe, Part part) {
    return resource(recipe, part) != null;
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
