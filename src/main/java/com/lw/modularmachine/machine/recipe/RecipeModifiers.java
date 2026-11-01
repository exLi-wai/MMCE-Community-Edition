package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiled (base + sum(add)) * product(multiply), with exact long bases. */
public final class RecipeModifiers {
  public static final int MAX_RULES = 256;
  private static final int MAX_DECIMAL_DIGITS = 256;
  private static final Set<String> FIELDS = Set.of("target", "io", "operation", "value", "affect_chance");
  private final Map<Key, Expression> expressions;

  public RecipeModifiers(List<RecipeModifier> rules) {
    if (rules.size() > MAX_RULES) {
      throw new IllegalArgumentException("Too many recipe modifiers");
    }
    var compiled = new HashMap<Key, Expression>();
    for (var rule : rules) {
      var key = new Key(rule.target(), rule.io(), rule.affectChance());
      var previous = compiled.getOrDefault(key, new Expression(BigDecimal.ZERO, BigDecimal.ONE));
      var value = bounded(BigDecimal.valueOf(rule.value()));
      var next = switch (rule.operation()) {
        case ADD -> new Expression(bounded(previous.add().add(value)), previous.multiply());
        case MULTIPLY -> new Expression(previous.add(), bounded(previous.multiply().multiply(value)));
      };
      compiled.put(key, next);
    }
    expressions = Map.copyOf(compiled);
  }

  public static List<RecipeModifier> parse(JsonElement source, String path) {
    if (source == null || !source.isJsonArray() || source.getAsJsonArray().size() > MAX_RULES) {
      throw new JsonParseException(path + ": Expected an array with at most " + MAX_RULES + " modifiers");
    }
    var result = new ArrayList<RecipeModifier>();
    int index = 0;
    for (var element : source.getAsJsonArray()) {
      String at = path + "[" + index++ + "]";
      if (!element.isJsonObject() || !FIELDS.containsAll(element.getAsJsonObject().keySet())) {
        throw new JsonParseException(at + ": Expected a modifier object with known fields");
      }
      var object = element.getAsJsonObject();
      for (var field : List.of("target", "io", "operation")) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
          throw new JsonParseException(at + "." + field + ": Expected a string");
        }
      }
      var number = object.get("value");
      if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
        throw new JsonParseException(at + ".value: Expected a finite number");
      }
      var chance = object.get("affect_chance");
      if (chance != null && (!chance.isJsonPrimitive() || !chance.getAsJsonPrimitive().isBoolean())) {
        throw new JsonParseException(at + ".affect_chance: Expected a boolean");
      }
      try {
        var exact = number.getAsBigDecimal();
        double value = exact.doubleValue();
        if (!Double.isFinite(value) || value == 0 && exact.signum() != 0) {
          throw new IllegalArgumentException("Modifier coefficient overflow or underflow");
        }
        result.add(RecipeModifier.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow());
      } catch (RuntimeException invalid) {
        throw new JsonParseException(at + ": " + invalid.getMessage(), invalid);
      }
    }
    new RecipeModifiers(result);

    return List.copyOf(result);
  }

  public long amount(RecipeModifier.Target target, RecipeModifier.Io io, long base, long minimum, long maximum) {
    if (base < 0 || minimum < 0 || maximum < minimum) {
      throw new IllegalArgumentException("Invalid modifier amount bounds");
    }
    var result = apply(new Key(target, io, false), BigDecimal.valueOf(base));
    if (result.signum() < 0) {
      throw new IllegalArgumentException("Modifier produced a negative " + target.serialized() + " amount");
    }
    long rounded = result.setScale(0, RoundingMode.HALF_UP).longValueExact();
    if (rounded < minimum || rounded > maximum) {
      throw new IllegalArgumentException("Modifier result is outside supported " + target.serialized() + " range");
    }

    return rounded;
  }

  public int duration(int base) {
    long result = amount(RecipeModifier.Target.DURATION, RecipeModifier.Io.INPUT, base, 0, Integer.MAX_VALUE);

    return (int) Math.max(1, result);
  }

  public double chance(RecipeModifier.Target target, RecipeModifier.Io io, double base) {
    if (!Double.isFinite(base) || base < 0 || base > 1) {
      throw new IllegalArgumentException("Invalid base probability");
    }
    var result = apply(new Key(target, io, true), BigDecimal.valueOf(base));

    return result.max(BigDecimal.ZERO).min(BigDecimal.ONE).doubleValue();
  }

  private BigDecimal apply(Key key, BigDecimal base) {
    var expression = expressions.get(key);

    return expression == null ? base : base.add(expression.add()).multiply(expression.multiply());
  }

  private static BigDecimal bounded(BigDecimal value) {
    if (value.precision() > MAX_DECIMAL_DIGITS || Math.abs((long) value.scale()) > MAX_DECIMAL_DIGITS) {
      throw new IllegalArgumentException("Modifier arithmetic exceeds decimal complexity bounds");
    }

    return value;
  }

  private record Key(RecipeModifier.Target target, RecipeModifier.Io io, boolean chance) {
  }

  private record Expression(BigDecimal add, BigDecimal multiply) {
  }
}
