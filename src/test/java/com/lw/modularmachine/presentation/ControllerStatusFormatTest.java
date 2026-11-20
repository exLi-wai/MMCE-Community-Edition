package com.lw.modularmachine.presentation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ControllerStatusFormatTest {
  @Test
  void progressHandlesLongRecipesAndTruncatesFractionalPercentages() {
    assertEquals("50%", ControllerStatusFormat.progressPercent(30_000_000, 60_000_000));
    assertEquals("66%", ControllerStatusFormat.progressPercent(2, 3));
  }

  @Test
  void progressHandlesMissingDurationAndClampsToValidPercentages() {
    assertEquals("0%", ControllerStatusFormat.progressPercent(10, 0));
    assertEquals("0%", ControllerStatusFormat.progressPercent(-1, 10));
    assertEquals("100%", ControllerStatusFormat.progressPercent(11, 10));
  }

  @Test
  void searchTimeDisplaysMillisecondsWithTwoDecimalPlaces() {
    assertEquals("1.23", ControllerStatusFormat.searchMilliseconds(1234));
    assertEquals("1.00", ControllerStatusFormat.searchMilliseconds(1000));
  }
}
