package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.util.math.ExactIntegers;
import java.math.BigDecimal;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Exact server recipe argument conversion; declaration errors retain their
 * original meaning.
 */
@ApiStatus.Internal
final class KubeJSRecipeArguments {
  private KubeJSRecipeArguments() {
  }

  static ResourceLocation identifier(String text, String field) {
    var id = text == null ? null : ResourceLocation.tryParse(text);
    if (id == null) {
      throw new IllegalArgumentException("Invalid machine recipe " + field + " ID: " + text);
    }
    return id;
  }

  static long integer(Number value, String field, long minimum, long maximum) {
    Objects.requireNonNull(value, field);
    if (ExactIntegers.unsafeFloatingInteger(value)) {
      throw new IllegalArgumentException("Large " + field + " values require an exact decimal string");
    }
    final long amount;
    try {
      amount = ExactIntegers.longValueExact(value);
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " requires an exact integer", invalid);
    }
    if (amount < minimum || amount > maximum) {
      throw new IllegalArgumentException(field + " is outside its integer range");
    }
    return amount;
  }

  static long integer(String value, String field, long minimum, long maximum) {
    Objects.requireNonNull(value, field);
    if (!value.matches("[0-9]+")) {
      throw new IllegalArgumentException(field + " requires an exact decimal integer string");
    }
    return integer(new BigDecimal(value), field, minimum, maximum);
  }

}
