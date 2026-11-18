package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;

/**
 * Neo Vitae catalyst identity and the number of independently reserved
 * launches.
 */
public record MeteorRecipeData(Optional<Launch> output) {
  public static final MeteorRecipeData NONE = new MeteorRecipeData(Optional.empty());
  public static final MapCodec<MeteorRecipeData> CODEC = Launch.CODEC.optionalFieldOf("meteor_output")
      .xmap(MeteorRecipeData::new, MeteorRecipeData::output);

  public MeteorRecipeData scaled(int instances) {
    return new MeteorRecipeData(
        output.map(launch -> new Launch(launch.catalyst(), Math.multiplyExact(launch.count(), instances))));
  }

  public record Launch(ResourceLocation catalyst, int count) {
    public static final int MAX_LAUNCHES = 128;
    private static final Codec<Launch> CODEC = RecordCodecBuilder.create(
        instance -> instance
            .group(
                ResourceLocation.CODEC.fieldOf("catalyst").forGetter(Launch::catalyst),
                Codec.intRange(1, MAX_LAUNCHES).optionalFieldOf("count", 1).forGetter(Launch::count))
            .apply(instance, Launch::new));

    public Launch {
      if (catalyst == null || count < 1 || count > MAX_LAUNCHES) {
        throw new IllegalArgumentException("Invalid meteor launch declaration");
      }
    }
  }
}
