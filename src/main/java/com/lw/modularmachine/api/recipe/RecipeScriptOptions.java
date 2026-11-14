package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable display text and pure callback identities; executable functions are
 * never recipe data.
 */
public record RecipeScriptOptions(List<String> tooltips, List<ItemCallbacks> itemCallbacks) {
  public static final int MAX_TOOLTIP_LINES = 64;
  public static final int MAX_TOOLTIP_LENGTH = 512;
  public static final int MAX_ITEM_CALLBACKS = 256;
  public static final int MAX_ROW_MODIFIERS = 16;
  public static final RecipeScriptOptions NONE = new RecipeScriptOptions(List.of(), List.of());
  public static final MapCodec<RecipeScriptOptions> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          Codec.STRING.listOf().optionalFieldOf("recipe_tooltips", List.of()).forGetter(RecipeScriptOptions::tooltips),
          ItemCallbacks.CODEC.listOf().optionalFieldOf("item_callbacks", List.of())
              .forGetter(RecipeScriptOptions::itemCallbacks))
          .apply(instance, RecipeScriptOptions::new));

  public RecipeScriptOptions {
    tooltips = List.copyOf(tooltips);
    itemCallbacks = List.copyOf(itemCallbacks);
    if (tooltips.size() > MAX_TOOLTIP_LINES || itemCallbacks.size() > MAX_ITEM_CALLBACKS) {
      throw new IllegalArgumentException("Too many recipe tooltip lines or item callback bindings");
    }
    for (String line : tooltips) {
      if (line.isBlank()
          || line.length() > MAX_TOOLTIP_LENGTH
          || line.indexOf('\n') >= 0
          || line.indexOf('\r') >= 0
          || line.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Recipe tooltips require nonempty bounded single lines");
      }
    }
    var rows = new HashSet<String>();
    for (var binding : itemCallbacks) {
      if (!rows.add(binding.io().serialized() + ":" + binding.row())) {
        throw new IllegalArgumentException("Duplicate recipe item callback row");
      }
    }
  }

  public void validateRows(int inputRows, int outputRows) {
    for (var binding : itemCallbacks) {
      int rows = binding.io() == RecipeModifier.Io.INPUT ? inputRows : outputRows;
      if (binding.row() >= rows) {
        throw new IllegalArgumentException("Recipe item callback refers to a missing row");
      }
    }
  }

  public record ItemCallbacks(
      RecipeModifier.Io io,
      int row,
      Optional<ResourceLocation> checker,
      List<ResourceLocation> modifiers) {
    public static final Codec<ItemCallbacks> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.STRING.xmap(RecipeModifier.Io::parse, RecipeModifier.Io::serialized).fieldOf("io")
                .forGetter(ItemCallbacks::io),
            Codec.INT.fieldOf("row").forGetter(ItemCallbacks::row),
            ResourceLocation.CODEC.optionalFieldOf("checker").forGetter(ItemCallbacks::checker),
            ResourceLocation.CODEC.listOf().optionalFieldOf("modifiers", List.of()).forGetter(ItemCallbacks::modifiers))
            .apply(instance, ItemCallbacks::new));

    public ItemCallbacks {
      Objects.requireNonNull(io, "io");
      Objects.requireNonNull(checker, "checker");
      modifiers = List.copyOf(modifiers);
      if (row < 0
          || row >= 256
          || modifiers.size() > MAX_ROW_MODIFIERS
          || checker.isEmpty() && modifiers.isEmpty()
          || checker.isPresent() && io != RecipeModifier.Io.INPUT
          || checker.isPresent() && checker.orElseThrow().toString().length() > 256
          || modifiers.stream().anyMatch(id -> id.toString().length() > 256)) {
        throw new IllegalArgumentException("Item checks require input rows and callback identities must be bounded");
      }
      if (modifiers.stream().distinct().count() != modifiers.size()) {
        throw new IllegalArgumentException("Duplicate recipe item modifier in one row");
      }
    }
  }
}
