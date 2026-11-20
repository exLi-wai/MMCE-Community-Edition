package com.lw.modularmachine.presentation;

import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ControllerStatusFormat {
  private ControllerStatusFormat() {
  }

  public static String progressPercent(int progressTicks, int durationTicks) {
    if (durationTicks <= 0) {
      return "0%";
    }
    long percent = 100L * progressTicks / durationTicks;
    return Math.clamp(percent, 0, 100) + "%";
  }

  public static String searchMilliseconds(int averageSearchMicros) {
    return String.format(Locale.ROOT, "%.2f", averageSearchMicros / 1000.0);
  }
}
