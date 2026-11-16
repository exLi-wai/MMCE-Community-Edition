package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import org.jetbrains.annotations.ApiStatus;

/**
 * Physical Chemical capability; mutations use the port's existing guarded
 * storage.
 */
@ApiStatus.Internal
record MekanismPortHandler(MachinePortBlockEntity port) implements IChemicalHandler {
  @Override
  public int getChemicalTanks() {
    return 1;
  }

  @Override
  public ChemicalStack getChemicalInTank(int tank) {
    return tank == 0 && port.schemaSupported() ? MekanismChemicalStacks
        .stack(port.chemicalTank().getStack()) : ChemicalStack.EMPTY;
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
    long accepted = port.chemicalTank().fill(MekanismChemicalStacks.data(resource), action.simulate());

    return accepted == 0 ? resource : resource.copyWithAmount(resource.getAmount() - accepted);
  }

  @Override
  public ChemicalStack extractChemical(int tank, long amount, Action action) {
    if (tank != 0 || port.kind().isInput() || !port.schemaSupported() || !MekanismChemicalContainers.current(port)) {
      return ChemicalStack.EMPTY;
    }

    return MekanismChemicalStacks.stack(port.chemicalTank().drain(amount, action.simulate()));
  }
}
