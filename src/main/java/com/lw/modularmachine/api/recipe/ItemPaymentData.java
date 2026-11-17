package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;

/** Immutable startup tool operations and furnace-fuel requirements. */
public record ItemPaymentData(List<Tool> tools, List<Fuel> fuels) {
  private static final int MAX_REQUIREMENTS = 256;
  public static final ItemPaymentData NONE = new ItemPaymentData(List.of(), List.of());
  public static final Codec<ItemPaymentData> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              Tool.CODEC.listOf().optionalFieldOf("tools", List.of()).forGetter(ItemPaymentData::tools),
              Fuel.CODEC.listOf().optionalFieldOf("fuels", List.of()).forGetter(ItemPaymentData::fuels))
          .apply(instance, ItemPaymentData::new));

  public ItemPaymentData {
    tools = List.copyOf(tools);
    fuels = List.copyOf(fuels);
    if (tools.size() > MAX_REQUIREMENTS || fuels.size() > MAX_REQUIREMENTS) {
      throw new IllegalArgumentException("Too many item payment requirements");
    }
    var rows = new HashSet<Integer>();
    for (var tool : tools) {
      if (!rows.add(tool.row())) {
        throw new IllegalArgumentException("Duplicate tool payment row");
      }
    }
  }

  public boolean isEmpty() {
    return tools.isEmpty() && fuels.isEmpty();
  }

  public boolean isTool(int row) {
    return tools.stream().anyMatch(tool -> tool.row() == row);
  }

  public record Tool(int row, int durability) {
    public static final Codec<Tool> CODEC = RecordCodecBuilder.create(
        instance -> instance
            .group(
                Codec.intRange(0, MAX_REQUIREMENTS - 1).fieldOf("row").forGetter(Tool::row),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("durability").forGetter(Tool::durability))
            .apply(instance, Tool::new));

    public Tool {
      if (row < 0 || row >= MAX_REQUIREMENTS || durability < 1) {
        throw new IllegalArgumentException("Tool operations require an input row and positive durability");
      }
    }
  }

  public record Fuel(long burnTime, double chance, boolean parallelUnaffected, String selector) {
    public static final Codec<Fuel> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.LONG.fieldOf("burn_time").forGetter(Fuel::burnTime),
            Codec.DOUBLE.optionalFieldOf("chance", 1D).forGetter(Fuel::chance),
            Codec.BOOL.optionalFieldOf("parallel_unaffected", false).forGetter(Fuel::parallelUnaffected),
            Codec.STRING.optionalFieldOf("selector", "").forGetter(Fuel::selector)).apply(instance, Fuel::new));

    public Fuel {
      ResourceSelector.require(selector);
      if (burnTime < 1 || !Double.isFinite(chance) || chance < 0 || chance > 1) {
        throw new IllegalArgumentException("Fuel requires positive burn time and probability within 0..1");
      }
    }
  }
}
