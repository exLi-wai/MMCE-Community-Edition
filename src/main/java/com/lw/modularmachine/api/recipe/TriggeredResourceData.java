package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Immutable scheduled IO. Mutable item/fluid prototypes are copied on capture
 * and access; execution and trigger progress belong to the paid run.
 */
public record TriggeredResourceData(List<Row> rows) {
  public static final int MAX_ROWS = 256;
  public static final TriggeredResourceData NONE = new TriggeredResourceData(List.of());
  public static final Codec<TriggeredResourceData> CODEC = Row.CODEC.listOf()
      .xmap(TriggeredResourceData::new, TriggeredResourceData::rows);
  public static final MapCodec<TriggeredResourceData> MAP_CODEC = CODEC.optionalFieldOf("triggered_resources", NONE);

  public TriggeredResourceData {
    rows = List.copyOf(rows);
    if (rows.size() > MAX_ROWS) {
      throw new IllegalArgumentException("Too many triggered resource rows");
    }
  }

  public boolean isEmpty() {
    return rows.isEmpty();
  }

  public record Row(
      String resource,
      RecipeModifier.Io io,
      int tick,
      boolean repeatable,
      long amount,
      double chance,
      String selector,
      Optional<ItemStack> item,
      Optional<FluidStack> fluid,
      Optional<ResourceLocation> chemical,
      boolean parallelUnaffected,
      String match) {
    public static final Codec<Row> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.STRING.fieldOf("resource").forGetter(Row::resource),
            Codec.STRING.xmap(RecipeModifier.Io::parse, RecipeModifier.Io::serialized).fieldOf("io").forGetter(Row::io),
            Codec.INT.fieldOf("tick").forGetter(Row::tick),
            Codec.BOOL.optionalFieldOf("repeatable", false).forGetter(Row::repeatable),
            Codec.LONG.fieldOf("amount").forGetter(Row::amount),
            Codec.DOUBLE.optionalFieldOf("chance", 1D).forGetter(Row::chance),
            Codec.STRING.optionalFieldOf("selector", "").forGetter(Row::selector),
            Codec.lazyInitialized(() -> ItemStack.SINGLE_ITEM_CODEC).optionalFieldOf("item").forGetter(Row::item),
            Codec.lazyInitialized(() -> FluidStack.CODEC).optionalFieldOf("fluid").forGetter(Row::fluid),
            ResourceLocation.CODEC.optionalFieldOf("chemical").forGetter(Row::chemical),
            Codec.BOOL.optionalFieldOf("parallel_unaffected", false).forGetter(Row::parallelUnaffected),
            Codec.STRING.optionalFieldOf("match", "exact").forGetter(Row::match)).apply(instance, Row::new));

    public Row {
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(match, "match");
      item = Objects.requireNonNull(item, "item").map(stack -> stack.copyWithCount(1));
      fluid = Objects.requireNonNull(fluid, "fluid").map(stack -> stack.copyWithAmount(1));
      Objects.requireNonNull(chemical, "chemical");
      if (io == null || tick < 1 || amount < 1 || !Double.isFinite(chance) || chance < 0 || chance > 1) {
        throw new IllegalArgumentException("Triggered IO needs positive time/quantity and probability within 0..1");
      }
      selector = ResourceSelector.require(selector);
      if (!match.equals("exact")
          && !(resource.equals("item") && io == RecipeModifier.Io.INPUT && match.equals("item"))) {
        throw new IllegalArgumentException("Only triggered item inputs may ignore components");
      }
      boolean validPrototype = switch (resource) {
        case "item" -> item.isPresent() && !item.orElseThrow().isEmpty() && fluid.isEmpty() && chemical.isEmpty();
        case "fluid" -> fluid.isPresent()
            && !fluid.orElseThrow().isEmpty()
            && item.isEmpty()
            && chemical.isEmpty()
            && amount <= Integer.MAX_VALUE;
        case "chemical" -> chemical.isPresent() && item.isEmpty() && fluid.isEmpty() && !parallelUnaffected;
        default -> false;
      };
      if (!validPrototype) {
        throw new IllegalArgumentException("Invalid triggered resource prototype or quantity rule");
      }
    }

    @Override
    public Optional<ItemStack> item() {
      return item.map(ItemStack::copy);
    }

    @Override
    public Optional<FluidStack> fluid() {
      return fluid.map(FluidStack::copy);
    }
  }
}
