package com.lw.modularmachine.integration.embers;

import com.lw.modularmachine.blockentity.EmberInputHatchBlockEntity;
import com.lw.modularmachine.registry.MachineRegistries;
import com.rekindled.embers.api.capabilities.EmbersCapabilities;
import com.rekindled.embers.power.DefaultEmberCapability;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EmbersIntegration {
  private EmbersIntegration() {
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(EmbersIntegration::registerCapabilities);
  }

  private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        EmbersCapabilities.EMBER_BLOCK_CAPABILITY,
        MachineRegistries.EMBER_INPUT_ENTITY.get(),
        (hatch, side) -> new InputCapability(hatch));
  }

  private static final class InputCapability extends DefaultEmberCapability {
    private final EmberInputHatchBlockEntity hatch;

    private InputCapability(EmberInputHatchBlockEntity hatch) {
      this.hatch = hatch;
    }

    @Override
    public double getEmber() {
      return hatch.stored() / 1_000_000.0;
    }

    @Override
    public double getEmberCapacity() {
      return hatch.capacity() / 1_000_000.0;
    }

    @Override
    public void setEmber(double amount) {
    }

    @Override
    public void setEmberCapacity(double amount) {
    }

    @Override
    public double addAmount(double amount, boolean doOperation) {
      if (!Double.isFinite(amount) || amount <= 0) {
        return 0;
      }
      long micros = BigDecimal.valueOf(Math.min(amount, getEmberCapacity())).movePointRight(6)
          .setScale(0, RoundingMode.DOWN).longValueExact();
      return hatch.receive(micros, !doOperation) / 1_000_000.0;
    }

    @Override
    public double removeAmount(double amount, boolean doOperation) {
      return 0;
    }

    @Override
    public void writeToNBT(CompoundTag tag) {
      tag.putDouble(EMBER, getEmber());
      tag.putDouble(EMBER_CAPACITY, getEmberCapacity());
    }

    @Override
    public boolean acceptsVolatile() {
      return hatch.acceptsVolatile();
    }
  }
}
