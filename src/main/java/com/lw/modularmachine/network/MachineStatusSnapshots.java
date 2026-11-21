package com.lw.modularmachine.network;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.component.PortResourceAccess;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import java.util.UUID;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.lw.modularmachine.network.MachinePayloads.FactoryStatusPayload;
import static com.lw.modularmachine.network.MachinePayloads.FactoryStatusThread;
import static com.lw.modularmachine.network.MachinePayloads.MAX_FACTORY_THREADS;
import static com.lw.modularmachine.network.MachinePayloads.PROTOCOL_VERSION;
import static com.lw.modularmachine.network.MachinePayloads.RESOURCE_PROTOCOL_VERSION;
import static com.lw.modularmachine.network.MachinePayloads.ResourcePortStatusPayload;
import org.jetbrains.annotations.ApiStatus;

/** Server snapshots for resource ports and factory status. */
@ApiStatus.Internal
final class MachineStatusSnapshots {
  private MachineStatusSnapshots() {
  }

  static FactoryStatusPayload factoryStatus(FactoryControllerBlockEntity factory) {
    var threads = factory.scheduler().channels().stream().limit(MAX_FACTORY_THREADS)
        .map(
            thread -> threadStatus(
                thread,
                factory.run(thread.threadId()).orElse(null),
                factory.recipeInterfaceFailure(thread.threadId())))
        .toList();
    var performance = factory.performance();
    return new FactoryStatusPayload(
        PROTOCOL_VERSION,
        factory.getBlockPos(),
        factory.machineId(),
        factory.scheduler().cursor(),
        factory.persistentStateValid(),
        factory.verificationState().name(),
        factory.verificationFailure().name(),
        performance.tickMicros(),
        performance.searchMicros(),
        threads);
  }

  private static FactoryStatusThread threadStatus(FactoryThreadState state, FrozenRecipeRun run, String recipeFailureMessage) {
    return new FactoryStatusThread(
        state.threadId(),
        state.machineId(),
        state.lockedRecipe(),
        state.enabled(),
        state.priority(),
        state.parallelLimit(),
        state.upgradeRevision(),
        state.upgradeSnapshot().speedNumerator(),
        state.upgradeSnapshot().speedDenominator(),
        state.upgradeSnapshot().energyNumerator(),
        state.upgradeSnapshot().energyDenominator(),
        state.upgradeSnapshot().fluidNumerator(),
        state.upgradeSnapshot().fluidDenominator(),
        state.state().name(),
        state.reason().name(),
        state.revision(),
        run == null ? new UUID(0, 0) : run.runId(),
        run == null ? "" : run.state().name(),
        run == null ? 0 : run.progress(),
        run == null ? 0 : run.duration(),
        recipeFailureMessage);
  }

  static ResourcePortStatusPayload resourceStatus(MachinePortBlockEntity port) {
    var fluid = port.kind().resource() == ResourceType.FLUID ? port.fluidTank().getFluid().copy() : FluidStack.EMPTY;
    var chemical = port.kind().resource() == ResourceType.CHEMICAL ? port.chemicalTank()
        .getStack() : ChemicalStackData.EMPTY;
    long amount = PortResourceAccess.require(port.kind().resource()).amount().read(port, 0);
    long capacity = PortResourceAccess.require(port.kind().resource()).capacity().read(port, 0);
    return new ResourcePortStatusPayload(
        RESOURCE_PROTOCOL_VERSION,
        port.getBlockPos(),
        port.kind(),
        port.tier().name(),
        amount,
        capacity,
        port.kind().resource() == ResourceType.ENERGY ? port.energy().transferLimit() : 0,
        fluid,
        chemical);
  }

}
