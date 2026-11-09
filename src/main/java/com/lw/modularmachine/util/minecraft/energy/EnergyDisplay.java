package com.lw.modularmachine.util.minecraft.energy;

import com.lw.modularmachine.config.MachineClientConfig;
import com.lw.modularmachine.util.Mods;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal display conversion; never changes energy storage or capability
 * units.
 */
@ApiStatus.Internal
public final class EnergyDisplay {
  public enum Unit {
    FE(1, "fe"),
    RF(1, "rf"),
    GT_EU(4, "gt_eu");

    private final int divisor;
    private final String name;

    Unit(int divisor, String name) {
      this.divisor = divisor;
      this.name = name;
    }

    public Component component() {
      return Component.translatable("tooltip.mmcece.energy_unit." + name);
    }

    public boolean converted() {
      return divisor != 1;
    }
  }

  private EnergyDisplay() {
  }

  public static Unit unit() {
    return MachineClientConfig.SPEC.isLoaded() ? MachineClientConfig.ENERGY_DISPLAY_TYPE.get() : Unit.FE;
  }

  public static Component unitComponent() {
    return unit().component();
  }

  public static String format(long amount) {
    return format(BigInteger.valueOf(amount), unit());
  }

  public static String format(BigInteger amount) {
    return format(amount, unit());
  }

  public static String format(long amount, Unit unit) {
    return format(BigInteger.valueOf(amount), unit);
  }

  public static String format(BigInteger amount, Unit unit) {
    var value = new BigDecimal(amount).divide(BigDecimal.valueOf(unit.divisor));
    var format = NumberFormat.getNumberInstance(Locale.ROOT);
    format.setMaximumFractionDigits(2);
    format.setMinimumFractionDigits(0);

    return format.format(value);
  }

  /** Legacy optional tooltip rows, with EU amounts labelled as FE conversions. */
  public static List<Unit> hatchTooltipUnits() {
    if (!MachineClientConfig.SPEC.isLoaded()) {
      return List.of(Unit.FE);
    }
    var units = new ArrayList<Unit>();
    if (MachineClientConfig.DISPLAY_FE_TOOLTIP.get()) {
      units.add(unit() == Unit.RF ? Unit.RF : Unit.FE);
    }
    if (Mods.GTM.isLoaded() && MachineClientConfig.DISPLAY_GT_EU_TOOLTIP.get()) {
      units.add(Unit.GT_EU);
    }

    return List.copyOf(units);
  }
}
