package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/** An immutable predicate tested at a formed machine's environment hatch. */
public sealed interface EnvironmentRequirement {
  Codec<EnvironmentRequirement> CODEC = Codec
      .lazyInitialized(() -> Type.CODEC.dispatch("type", EnvironmentRequirement::type, type -> type.payload));

  enum Type implements StringRepresentable {
    DIMENSION("dimension", Dimension.CODEC),
    BIOME("biome", Biome.CODEC),
    TIME("time", Time.CODEC),
    MOON_PHASE("moonphase", MoonPhase.CODEC),
    ALTITUDE("altitude", Altitude.CODEC),
    WEATHER("weather", Weather.CODEC);

    private static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
    private final String name;
    private final MapCodec<? extends EnvironmentRequirement> payload;

    Type(String name, MapCodec<? extends EnvironmentRequirement> payload) {
      this.name = name;
      this.payload = payload;
    }

    @Override
    public String getSerializedName() {
      return name;
    }
  }

  Type type();

  record Dimension(List<ResourceLocation> dimensions) implements EnvironmentRequirement {
    private static final MapCodec<Dimension> CODEC = ResourceLocation.CODEC.sizeLimitedListOf(16).fieldOf("dimensions")
        .xmap(Dimension::new, Dimension::dimensions);

    public Dimension {
      dimensions = requireValues(dimensions, 16);
    }

    @Override
    public Type type() {
      return Type.DIMENSION;
    }
  }

  record Biome(List<ResourceLocation> biomes) implements EnvironmentRequirement {
    private static final MapCodec<Biome> CODEC = ResourceLocation.CODEC.sizeLimitedListOf(16).fieldOf("biomes")
        .xmap(Biome::new, Biome::biomes);

    public Biome {
      biomes = requireValues(biomes, 16);
    }

    @Override
    public Type type() {
      return Type.BIOME;
    }
  }

  record Time(int begin, int end) implements EnvironmentRequirement {
    private static final MapCodec<Time> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            Codec.intRange(0, 23999).fieldOf("begin").forGetter(Time::begin),
            Codec.intRange(0, 23999).fieldOf("end").forGetter(Time::end)).apply(instance, Time::new));

    public Time {
      if (begin < 0 || end < 0 || begin >= 24000 || end >= 24000) {
        throw new IllegalArgumentException("Time must be within 0..23999");
      }
    }

    public boolean contains(long dayTime) {
      long time = Math.floorMod(dayTime, 24000);
      return begin <= end ? time >= begin && time <= end : time >= begin || time <= end;
    }

    @Override
    public Type type() {
      return Type.TIME;
    }
  }

  record MoonPhase(List<Integer> moonphases) implements EnvironmentRequirement {
    private static final MapCodec<MoonPhase> CODEC = Codec.intRange(0, 7).sizeLimitedListOf(8).fieldOf("moonphases")
        .xmap(MoonPhase::new, MoonPhase::moonphases);

    public MoonPhase {
      moonphases = requireValues(moonphases, 8);
      if (moonphases.stream().anyMatch(phase -> phase < 0 || phase > 7)) {
        throw new IllegalArgumentException("Moon phase must be within 0..7");
      }
    }

    @Override
    public Type type() {
      return Type.MOON_PHASE;
    }
  }

  record Altitude(int begin, int end) implements EnvironmentRequirement {
    private static final MapCodec<Altitude> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            Codec.INT.fieldOf("begin").forGetter(Altitude::begin),
            Codec.INT.fieldOf("end").forGetter(Altitude::end)).apply(instance, Altitude::new));

    public Altitude {
      if (begin > end) {
        throw new IllegalArgumentException("Altitude minimum exceeds maximum");
      }
    }

    @Override
    public Type type() {
      return Type.ALTITUDE;
    }
  }

  record Weather(Kind weather) implements EnvironmentRequirement {
    private static final MapCodec<Weather> CODEC = StringRepresentable.fromEnum(Kind::values).fieldOf("weather")
        .xmap(Weather::new, Weather::weather);

    public Weather {
      Objects.requireNonNull(weather, "weather");
    }

    @Override
    public Type type() {
      return Type.WEATHER;
    }

    public enum Kind implements StringRepresentable {
      SUNNY,
      RAINING,
      SNOWING,
      THUNDERING;

      @Override
      public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
      }
    }
  }

  private static <T> List<T> requireValues(List<T> values, int maximum) {
    var copy = List.copyOf(values);
    if (copy.isEmpty() || copy.size() > maximum) {
      throw new IllegalArgumentException("Environment predicate requires 1.." + maximum + " values");
    }
    return copy;
  }
}
