package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.BufferedRecipeHatchBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.registry.MachineRegistries;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class HeatHatchBlockEntity extends BufferedRecipeHatchBlockEntity implements IHeatHandler, TickingRecipeHatchBlock.ServerTicking {
  private static final int UNITS_PER_HEAT = 1_000;
  private static final double HEAT_CAPACITY = 1;
  private static final double INVERSE_CONDUCTION = 5;
  private final boolean input;

  public HeatHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.HEAT_ENTITY.get(), position, state, MachineConfig.heatHatchCapacity(), "mekanism_heat",
        state.is(MachineRegistries.HEAT_INPUT.get()));
    input = state.is(MachineRegistries.HEAT_INPUT.get());
  }

  @Override
  public int getHeatCapacitorCount() {
    return 1;
  }

  @Override
  public double getTemperature(int capacitor) {
    return HeatAPI.getAmbientTemp(level, worldPosition) + stored() / (HEAT_CAPACITY * UNITS_PER_HEAT);
  }

  @Override
  public double getInverseConduction(int capacitor) {
    return INVERSE_CONDUCTION;
  }

  @Override
  public double getHeatCapacity(int capacitor) {
    return HEAT_CAPACITY;
  }

  @Override
  public void handleHeat(int capacitor, double transfer) {
    exchangeHeat(transfer);
  }

  private long exchangeHeat(double transfer) {
    if (!Double.isFinite(transfer) || transfer == 0 || !live() || RecipeLifecyclePublisher.isDispatching()) {
      return 0;
    }
    long requested = (long) (Math.abs(transfer) * UNITS_PER_HEAT);
    return exchangeHeat(requested, transfer > 0);
  }

  private long exchangeHeat(long requested, boolean adding) {
    long amount = Math.min(requested, adding ? space() : available());
    if (amount == 0) {
      return 0;
    }
    if (adding && input) {
      return receive(amount, false);
    }
    if (!adding && !input) {
      return extract(amount);
    }
    try (var lease = acquireRecipeBuffer()) {
      if (!(adding ? lease.insertExact(amount) : lease.extractExact(amount))) {
        return 0;
      }
      lease.resolve(true);
    }
    setChanged();
    return amount;
  }

  @Override
  public void tickHatch() {
    if (input || !live() || available() == 0 || RecipeLifecyclePublisher.isDispatching()) {
      return;
    }
    for (var direction : Direction.values()) {
      var position = worldPosition.relative(direction);
      if (!level.hasChunkAt(position)) {
        continue;
      }
      var target = level.getCapability(Capabilities.HEAT, position, direction.getOpposite());
      if (target != null && target != this && target.getHeatCapacitorCount() > 0) {
        conductTo(target);
      }
    }
  }

  private void conductTo(IHeatHandler target) {
    double difference = getTemperature(0) - target.getTotalTemperature();
    double transfer = difference / (INVERSE_CONDUCTION + target.getTotalInverseConduction());
    if (!Double.isFinite(transfer) || transfer <= 0) {
      return;
    }
    long requested = (long) (transfer * UNITS_PER_HEAT);
    if (requested == 0 && target instanceof HeatHatchBlockEntity other && other.input) {
      requested = 1;
    }
    long offered = Math.min(available(), requested);
    if (offered <= 0) {
      return;
    }
    if (target instanceof HeatHatchBlockEntity other) {
      extract(other.exchangeHeat(offered, true));
    } else {
      long sent = extract(offered);
      if (sent > 0) {
        target.handleHeat(sent / (double) UNITS_PER_HEAT);
      }
    }
  }
}
