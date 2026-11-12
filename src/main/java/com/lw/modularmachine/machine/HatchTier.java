package com.lw.modularmachine.machine;

import java.util.List;

/**
 * Initial defaults follow the old ItemBusSize, FluidHatchSize and
 * EnergyHatchData.
 */
public record HatchTier(String name, long capacity, long transferLimit) {

  public HatchTier {
    if (name == null || !name.matches("[a-z_]+") || capacity <= 0 || transferLimit < 0) {
      throw new IllegalArgumentException("Invalid hatch tier");
    }
  }

  public static final List<HatchTier> ITEMS = List.of(
      new HatchTier("tiny", 1, 0),
      new HatchTier("small", 4, 0),
      new HatchTier("normal", 6, 0),
      new HatchTier("reinforced", 9, 0),
      new HatchTier("big", 12, 0),
      new HatchTier("huge", 16, 0),
      new HatchTier("ludicrous", 32, 0));

  public static final List<HatchTier> FLUIDS = List.of(
      new HatchTier("tiny", 100, 0),
      new HatchTier("small", 400, 0),
      new HatchTier("normal", 1_000, 0),
      new HatchTier("reinforced", 2_000, 0),
      new HatchTier("big", 4_500, 0),
      new HatchTier("huge", 8_000, 0),
      new HatchTier("ludicrous", 16_000, 0),
      new HatchTier("vacuum", 32_000, 0));

  public static final List<HatchTier> ENERGY = List.of(
      new HatchTier("tiny", 2_048, 128),
      new HatchTier("small", 4_096, 512),
      new HatchTier("normal", 8_192, 512),
      new HatchTier("reinforced", 16_384, 2_048),
      new HatchTier("big", 32_768, 8_192),
      new HatchTier("huge", 131_072, 32_768),
      new HatchTier("ludicrous", 524_288, 131_072),
      new HatchTier("ultimate", 2_097_152, 131_072));

  public static final List<HatchTier> CHEMICALS = List.of(
      new HatchTier("tiny", 100, 0),
      new HatchTier("small", 400, 0),
      new HatchTier("normal", 1_000, 0),
      new HatchTier("reinforced", 2_000, 0),
      new HatchTier("big", 4_500, 0),
      new HatchTier("huge", 8_000, 0),
      new HatchTier("ludicrous", 16_000, 0),
      new HatchTier("vacuum", 32_000, 0));

  public static List<HatchTier> forResource(PortKind.Resource resource) {
    return switch (resource) {
      case ITEM -> ITEMS;
      case FLUID -> FLUIDS;
      case ENERGY -> ENERGY;
      case CHEMICAL -> CHEMICALS;
    };
  }
}
