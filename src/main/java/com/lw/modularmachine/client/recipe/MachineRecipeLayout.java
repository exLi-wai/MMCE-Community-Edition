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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

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
  record ItemDisplay(ItemStack stack, long amount) {
    ItemDisplay(MachineRecipe.ItemAmount amount) {
      this(amount.displayStack(), amount.amount());
    }
  }

  private record Geometry(List<Part> parts, int width, int height, int arrowX, int arrowY, boolean hasAdditional) {
  }

  private final LegacyRecipeResources resources;
  private final List<ItemDisplay> inputItems, outputItems;
  private final List<FluidStack> inputFluids, outputFluids;
  public final List<Part> parts;
  public final int width, height, arrowX, arrowY;

  private MachineRecipeLayout(MachineRecipe recipe, LegacyRecipeResources resources, Geometry geometry) {
    this.resources = resources;
    inputItems = recipe.inputs().stream().map(ItemDisplay::new).toList();
    outputItems = recipe.outputs().stream().map(ItemDisplay::new).toList();
    inputFluids = MachineRecipeDisplay.fluids(recipe, true);
    outputFluids = MachineRecipeDisplay.fluids(recipe, false);
    parts = geometry.parts();
    arrowX = geometry.arrowX();
    arrowY = geometry.arrowY();
    var lines = MachineRecipeDisplay.text(recipe);
    int textWidth = lines.stream().mapToInt(Minecraft.getInstance().font::width).max().orElse(0);
    width = Math.max(geometry.width(), textWidth + 4);
    height = Math.max(
        18,
        geometry.height() + Math
            .max(MachineRecipeDisplay.textHeight(recipe, lines.size()), geometry.hasAdditional() ? 9 : 0));
  }

  public static Map<MachineRecipe, MachineRecipeLayout> perRecipe(List<MachineRecipe> recipes) {
    var layouts = new IdentityHashMap<MachineRecipe, MachineRecipeLayout>();
    var geometries = new HashMap<IntList, Geometry>();
    for (var recipe : recipes) {
      var resources = new LegacyRecipeResources(recipe);
      var geometry = geometries.computeIfAbsent(counts(recipe, resources), MachineRecipeLayout::geometry);
      layouts.put(recipe, new MachineRecipeLayout(recipe, resources, geometry));
    }
    return layouts;
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
    return new Geometry(List.copyOf(result), nextX, Math.max(highest, arrowY + 15), arrowX, arrowY, hasAdditional);
  }

  private static int count(MachineRecipe recipe, LegacyRecipeResources resources, Kind kind, boolean input) {
    return switch (kind) {
      case ENERGY -> (input ? recipe.energyPerTick() : recipe.energyOutputPerTick()) > 0 ? 1 : 0;
      case FLUID -> input ? recipe.fluidInputRequirements().size() + recipe.fluidPerTickInputs().size() : recipe
          .fluidOutputRequirements().size() + recipe.fluidPerTickOutputs().size();
      case ITEM -> input ? recipe.inputs().size() : recipe.outputs().size();
      case CHEMICAL -> MachineRecipeDisplay.chemicalCount(recipe, input);
      default -> resources.count(kind, input);
    };
  }

  ItemDisplay item(Part part) {
    return (part.input() ? inputItems : outputItems).get(part.index());
  }

  FluidStack fluid(Part part) {
    return (part.input() ? inputFluids : outputFluids).get(part.index());
  }

  LegacyRecipeResources.Entry resource(Part part) {
    return resources.entry(part);
  }

  public boolean hasResource(Part part) {
    return part.kind() == Kind.ENERGY || resource(part) != null;
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
