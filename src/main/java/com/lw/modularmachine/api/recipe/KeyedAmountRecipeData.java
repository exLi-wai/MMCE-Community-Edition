package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Exact amounts of distinct registry IDs at payment and completion. */
public record KeyedAmountRecipeData(Map<ResourceLocation, Long> input, Map<ResourceLocation, Long> output) {
  public static final KeyedAmountRecipeData NONE = new KeyedAmountRecipeData(Map.of(), Map.of());
  public static final int MAX_KEYS = 64;
  public static final ResourceLocation ANY_STARLIGHT = ResourceLocation.fromNamespaceAndPath("mmcece", "any");

  public KeyedAmountRecipeData {
    input = checked(input);
    output = checked(output);
  }

  public static MapCodec<KeyedAmountRecipeData> codec(String key) {
    var amounts = Codec.unboundedMap(ResourceLocation.CODEC, BufferedAmountRecipeData.AMOUNT);
    return RecordCodecBuilder.mapCodec(
        instance -> instance
            .group(
                amounts.optionalFieldOf(key + "_input", Map.of()).forGetter(KeyedAmountRecipeData::input),
                amounts.optionalFieldOf(key + "_output", Map.of()).forGetter(KeyedAmountRecipeData::output))
            .apply(instance, KeyedAmountRecipeData::new));
  }

  public boolean present() {
    return !input.isEmpty() || !output.isEmpty();
  }

  public KeyedAmountRecipeData scaled(int instances) {
    return new KeyedAmountRecipeData(scale(input, instances), scale(output, instances));
  }

  private static Map<ResourceLocation, Long> checked(Map<ResourceLocation, Long> values) {
    if (values.size() > MAX_KEYS || values.values().stream().anyMatch(amount -> amount <= 0)) {
      throw new IllegalArgumentException("Invalid keyed recipe resource amounts");
    }
    return Map.copyOf(values);
  }

  private static Map<ResourceLocation, Long> scale(Map<ResourceLocation, Long> values, int instances) {
    var result = new LinkedHashMap<ResourceLocation, Long>();
    values.forEach((key, amount) -> result.put(key, Math.multiplyExact(amount, instances)));
    return result;
  }
}
