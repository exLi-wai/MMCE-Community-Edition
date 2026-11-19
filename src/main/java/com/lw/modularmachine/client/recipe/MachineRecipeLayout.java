package com.lw.modularmachine.client.recipe;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

public final class MachineRecipeLayout {
  private static final List<Kind> KINDS = List.of(Kind.values());

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
  private record Geometry(List<Part> parts, int width, int height, int arrowX, int arrowY, boolean hasAdditional) {
  }

  private record TextSize(int width, int height) {
  }

  private final Map<MachineRecipe, LegacyRecipeResources> resources;
  public final List<Part> parts;
  public final int width, height, arrowX, arrowY;

  public MachineRecipeLayout(List<MachineRecipe> recipes) {
    this(buildResources(recipes), recipes);
  }

  private MachineRecipeLayout(Map<MachineRecipe, LegacyRecipeResources> resources, List<MachineRecipe> recipes) {
    this(resources, geometry(maxCounts(recipes, resources)), measureText(recipes), false);
  }

  private MachineRecipeLayout(Map<MachineRecipe, LegacyRecipeResources> resources, Geometry geometry, TextSize text, boolean perRecipe) {
    this.resources = resources;
    parts = geometry.parts();
    arrowX = geometry.arrowX();
    arrowY = geometry.arrowY();
    width = Math.max(geometry.width(), text.width() + 4);
    int contentHeight = perRecipe ? Math.max(geometry.height(), arrowY + 15) : geometry.height();
    height = Math.max(18, contentHeight + Math.max(text.height(), geometry.hasAdditional() ? 9 : 0));
  }

  public static Map<MachineRecipe, MachineRecipeLayout> perRecipe(List<MachineRecipe> recipes) {
    var layouts = new IdentityHashMap<MachineRecipe, MachineRecipeLayout>();
    var geometries = new HashMap<IntList, Geometry>();
    var resources = buildResources(recipes);
    for (var recipe : recipes) {
      var geometry = geometries.computeIfAbsent(counts(recipe, resources.get(recipe)), MachineRecipeLayout::geometry);
      layouts.put(recipe, new MachineRecipeLayout(resources, geometry, measureText(recipe), true));
    }
    return layouts;
  }

  private static Map<MachineRecipe, LegacyRecipeResources> buildResources(List<MachineRecipe> recipes) {
    var resources = new IdentityHashMap<MachineRecipe, LegacyRecipeResources>();
    recipes.forEach(recipe -> resources.put(recipe, new LegacyRecipeResources(recipe)));
    return resources;
  }

  private static IntList maxCounts(List<MachineRecipe> recipes, Map<MachineRecipe, LegacyRecipeResources> resources) {
    var maximums = new IntArrayList(new int[KINDS.size() * 2]);
    for (var recipe : recipes) {
      var counts = counts(recipe, resources.get(recipe));
      for (int index = 0; index < counts.size(); index++) {
        maximums.set(index, Math.max(maximums.getInt(index), counts.getInt(index)));
      }
    }
    return maximums;
  }

  private static IntList counts(MachineRecipe recipe, LegacyRecipeResources resources) {
    var counts = new IntArrayList(KINDS.size() * 2);
    for (boolean input : new boolean[]{true, false}) {
      for (Kind kind : KINDS) {
        counts.add(count(recipe, resources, kind, input));
      }
    }
    return counts;
  }

  private static Geometry geometry(IntList counts) {
    var result = new ArrayList<Part>();
    int nextX = 4, highest = 0, arrowX = 8;
    for (boolean input : new boolean[]{true, false}) {
      int offset = input ? 0 : KINDS.size();
      for (Kind kind : input ? KINDS : KINDS.reversed()) {
        int count = counts.getInt(offset + kind.ordinal());
        if (count == 0) {
          continue;
        }
        int columns = switch (kind) {
          case ITEM -> itemColumns(count);
          case FLUID, CHEMICAL, ESSENTIA, STARLIGHT -> Math.max((count + 3) / 4, 1);
          case ENVIRONMENT -> 3;
          case CONSTELLATION -> Math.max((count + 1) / 2, 1);
          default -> 1;
        };
        int start = nextX;
        for (int index = 0; index < count; index++) {
          int partX = start + index % columns * (kind.width + kind.gap);
          int partY = index / columns * (kind.height + kind.gap);
          result.add(new Part(kind, input, index, partX, partY));
          nextX = Math.max(nextX, partX + kind.width + kind.gap);
          highest = Math.max(highest, partY + kind.height);
        }
      }
      if (input) {
        arrowX = nextX + 4;
        nextX = arrowX + 26;
      }
    }
    int arrowY = highest / 2 / 2;
    boolean hasAdditional = result.stream().anyMatch(part -> switch (part.kind()) {
      case ENERGY, FLUID, ITEM -> false;
      default -> true;
    });
    return new Geometry(
        List.copyOf(result),
        nextX,
        Math.max(highest, hasAdditional ? arrowY + 15 : 0),
        arrowX,
        arrowY,
        hasAdditional);
  }

  private static TextSize measureText(List<MachineRecipe> recipes) {
    int width = 0;
    int height = 0;
    for (var recipe : recipes) {
      var text = measureText(recipe);
      width = Math.max(width, text.width());
      height = Math.max(height, text.height());
    }
    return new TextSize(width, height);
  }

  private static TextSize measureText(MachineRecipe recipe) {
    var text = MachineRecipeDisplay.text(recipe);
    int width = 0;
    for (var line : text) {
      width = Math.max(width, Minecraft.getInstance().font.width(line));
    }
    int height = text.size() * 9 + (recipe.energyPerTick() > 0 ? 2 : 0) + (recipe.interfaceInputs().isEmpty() ? 0 : 2);
    return new TextSize(width, height);
  }

  private static int count(MachineRecipe recipe, LegacyRecipeResources resources, Kind kind, boolean input) {
    return switch (kind) {
      case ENERGY -> input && recipe.energyPerTick() > 0 ? 1 : 0;
      case FLUID -> input ? recipe.fluidInputs().size() + recipe.fluidPerTickInputs().size() : recipe.fluidOutputs()
          .size() + recipe.fluidPerTickOutputs().size();
      case ITEM -> input ? recipe.inputs().size() : recipe.outputs().size();
      case CHEMICAL -> MachineRecipeDisplay.chemicalCount(recipe, input);
      default -> resources.count(kind, input);
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
