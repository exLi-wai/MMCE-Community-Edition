package com.lw.modularmachine.util.math;

import java.math.BigDecimal;
import org.jetbrains.annotations.ApiStatus;

/** Pure exact integer conversion; callers retain range and error policy. */
@ApiStatus.Internal
public final class ExactIntegers {
  private ExactIntegers() {
  }

  /**
   * Script floating values outside this range require an exact representation.
   */
  public static boolean unsafeFloatingInteger(Number value) {
    return (value instanceof Double || value instanceof Float) && Math.abs(value.doubleValue()) > 9007199254740991D;
  }

  /** Preserves decimal parsing, exactness, overflow and null exceptions. */
  public static long longValueExact(Number value) {
    return new BigDecimal(value.toString()).longValueExact();
  }
}
