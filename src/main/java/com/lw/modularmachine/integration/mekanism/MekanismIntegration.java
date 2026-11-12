package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.registry.MachineRegistries;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Optional Mekanism adapter. Loaded only after the central Mods gate succeeds.
 */
@ApiStatus.Internal
public final class MekanismIntegration implements ChemicalPlatform.Adapter {
  private MekanismIntegration() {
  }

  public static void initialize(IEventBus bus) {
    ChemicalPlatform.install(new MekanismIntegration());
    bus.addListener(MekanismIntegration::registerCapabilities);
  }

  private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.CHEMICAL.block(),
        MachineRegistries.PORT_ENTITY.get(),
        (port, side) -> port.schemaSupported()
            && port.kind().resource() == PortKind.Resource.CHEMICAL ? new PortHandler(port) : null);
  }

  @Override
  public boolean contains(ResourceLocation chemical) {
    return MekanismAPI.CHEMICAL_REGISTRY.getHolder(chemical)
        .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY)).isPresent();
  }

  @Override
  public boolean hasContainer(ItemStack stack) {
    return !stack.isEmpty() && Capabilities.CHEMICAL.getCapability(stack) != null;
  }

  @Override
  public ChemicalStackData sampleContainer(ItemStack stack) {
    var handler = Capabilities.CHEMICAL.getCapability(stack);
    if (handler == null || handler.getChemicalTanks() > 64) {
      return ChemicalStackData.EMPTY;
    }
    for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
      var chemical = handler.getChemicalInTank(tank);
      if (!chemical.isEmpty()) {
        return data(chemical).withAmount(1);
      }
    }
    return ChemicalStackData.EMPTY;
  }

  @Override
  public boolean interact(Player player, InteractionHand hand, MachinePortBlockEntity port) {
    return MekanismChemicalContainers.interact(player, hand, port);
  }

  static ChemicalStackData data(ChemicalStack stack) {
    if (stack.isEmpty()) {
      return ChemicalStackData.EMPTY;
    }
    var key = stack.getChemicalHolder().getKey();

    return new ChemicalStackData(
        key == null ? MekanismAPI.CHEMICAL_REGISTRY.getDefaultKey() : key.location(),
        stack.getAmount());
  }

  static ChemicalStack stack(ChemicalStackData value) {
    if (value.isEmpty()) {
      return ChemicalStack.EMPTY;
    }
    var chemical = MekanismAPI.CHEMICAL_REGISTRY.getHolder(value.chemical())
        .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
        .orElseThrow(() -> new IllegalArgumentException("Unknown chemical: " + value.chemical()));

    return new ChemicalStack(chemical, value.amount());
  }

  private record PortHandler(MachinePortBlockEntity port) implements IChemicalHandler {
    @Override
    public int getChemicalTanks() {
      return 1;
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {
      return tank == 0 && port.schemaSupported() ? stack(port.chemicalTank().getStack()) : ChemicalStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {
      throw new UnsupportedOperationException("Chemical hatch capabilities require insert/extract operations");
    }

    @Override
    public long getChemicalTankCapacity(int tank) {
      return tank == 0 ? port.chemicalTank().getCapacity() : 0;
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {
      return tank == 0 && port.schemaSupported() && port.kind().isInput() && !stack.isEmpty();
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack resource, Action action) {
      if (!isValid(tank, resource) || !MekanismChemicalContainers.current(port)) {
        return resource;
      }
      long accepted = port.chemicalTank().fill(data(resource), action.simulate());

      return accepted == 0 ? resource : resource.copyWithAmount(resource.getAmount() - accepted);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {
      if (tank != 0 || port.kind().isInput() || !port.schemaSupported() || !MekanismChemicalContainers.current(port)) {
        return ChemicalStack.EMPTY;
      }

      return stack(port.chemicalTank().drain(amount, action.simulate()));
    }
  }
}
