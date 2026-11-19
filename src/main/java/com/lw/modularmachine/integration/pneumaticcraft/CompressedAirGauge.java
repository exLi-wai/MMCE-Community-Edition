package com.lw.modularmachine.integration.pneumaticcraft;

import me.desht.pneumaticcraft.client.render.pressure_gauge.PressureGaugeRenderer2D;
import me.desht.pneumaticcraft.lib.PneumaticValues;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CompressedAirGauge {
  private CompressedAirGauge() {
  }

  public static void render(GuiGraphics graphics, Font font, float pressure, float dangerPressure, float criticalPressure, int x, int y) {
    PressureGaugeRenderer2D.drawPressureGauge(graphics, font, -1, criticalPressure, dangerPressure, 0, pressure, x, y);
  }

  public static void renderRecipe(GuiGraphics graphics, Font font, float minimum, int x, int y, float progress) {
    float danger = PneumaticValues.DANGER_PRESSURE_TIER_TWO;
    float maximum = Math.max(PneumaticValues.MAX_PRESSURE_TIER_TWO, minimum);
    float target = minimum == 0 ? danger : minimum;
    graphics.flush();
    PressureGaugeRenderer2D.drawPressureGauge(
        graphics,
        font,
        -1,
        maximum,
        danger,
        minimum == 0 ? -Float.MAX_VALUE : minimum,
        target * progress,
        x,
        y);
  }
}
