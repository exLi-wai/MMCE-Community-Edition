package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import java.util.Objects;

/** Pure numeric rule; no live controller or mutable storage reference. */
public record RecipeModifier(Target target, Io io, Operation operation, double value, boolean affectChance) {
  public static final Codec<RecipeModifier> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
          Codec.STRING.fieldOf("target").forGetter(rule -> rule.target().serialized()),
          Codec.STRING.fieldOf("io").forGetter(rule -> rule.io().serialized()),
          Codec.STRING.fieldOf("operation").forGetter(rule -> rule.operation().serialized()),
          Codec.DOUBLE.fieldOf("value").forGetter(RecipeModifier::value),
          Codec.BOOL.optionalFieldOf("affect_chance", false).forGetter(RecipeModifier::affectChance)).apply(
              instance,
              (target, io, operation, value, chance) -> new RecipeModifier(
                  Target.parse(target),
                  Io.parse(io),
                  Operation.parse(operation),
                  value,
                  chance)));

  public RecipeModifier {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(io, "io");
    Objects.requireNonNull(operation, "operation");
    if (!Double.isFinite(value)
        || operation == Operation.MULTIPLY && value < 0
        || (target == Target.DURATION || target == Target.ENERGY) && io != Io.INPUT
        || affectChance && (target != Target.ITEM || io != Io.OUTPUT)) {
      throw new IllegalArgumentException("Unsupported modifier target/IO/chance or invalid numeric value");
    }
  }

  public enum Target {
    ITEM,
    FLUID,
    ENERGY,
    DURATION;

    public String serialized() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Target parse(String value) {
      return switch (value) {
        case "item" -> ITEM;
        case "fluid" -> FLUID;
        case "energy" -> ENERGY;
        case "duration" -> DURATION;
        default -> throw new IllegalArgumentException("Unknown modifier target: " + value);
      };
    }
  }

  public enum Io {
    INPUT,
    OUTPUT;

    public String serialized() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Io parse(String value) {
      return switch (value) {
        case "input" -> INPUT;
        case "output" -> OUTPUT;
        default -> throw new IllegalArgumentException("Unknown modifier IO: " + value);
      };
    }
  }

  public enum Operation {
    ADD,
    MULTIPLY;

    public String serialized() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Operation parse(String value) {
      return switch (value) {
        case "add" -> ADD;
        case "multiply" -> MULTIPLY;
        default -> throw new IllegalArgumentException("Unknown modifier operation: " + value);
      };
    }
  }
}
