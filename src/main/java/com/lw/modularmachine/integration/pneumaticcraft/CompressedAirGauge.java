package com.lw.modularmachine.integration.pneumaticcraft;

import me.desht.pneumaticcraft.client.render.pressure_gauge.PressureGaugeRenderer2D;
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
}
