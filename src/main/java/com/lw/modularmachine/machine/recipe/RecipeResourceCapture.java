package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.component.PortResourceAccess;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.recipe.RecipeResourceCapture.CaptureMode;
import com.lw.modularmachine.machine.recipe.RecipeTicketProof.SmartSnapshot;

/**
 * Main-thread physical capture and pattern views; final ticket validation
 * remains mandatory.
 */
@ApiStatus.Internal
final class RecipeResourceCapture {
  private RecipeResourceCapture() {
  }

  enum CaptureMode {
    ALL,
    OUTPUTS,
    ENERGY_INPUTS,
    ENERGY_OUTPUTS,
    FLUID_INPUTS,
    CHEMICAL_INPUTS
  }

  static Optional<RecipeExecutionTicket> capture(StructureService.StructureSubject owner, CaptureMode mode) {
    if (mode == CaptureMode.ALL
        && owner instanceof MachineControllerBlockEntity ordinary
        && owner.structureLevel() instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Ordinary upgrade source capture");
      ordinary.refreshUpgradeSources();
    }
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    ServerThreadGuard.require(level, "Recipe resource access");
    var registration = owner.structureRegistration();
    if (registration == null
        || !registration.canCommit(level)
        || owner instanceof MachineControllerBlockEntity ordinary && !ordinary.canCommitProduction()) {
      return Optional.empty();
    }
    var ports = new ArrayList<RecipePortSnapshot>();
    var interfaces = new ArrayList<SmartSnapshot>();
    var positions = new HashSet<BlockPos>();
    var definition = registration.pattern.definition;
    for (int index = 0; index < definition.parts().size(); index++) {
      var part = definition.parts().get(index);
      if (mode == CaptureMode.ALL && !part.interfaceType().isEmpty()) {
        var position = registration.pattern.position(index);
        if (!level.hasChunkAt(position)
            || !(level.getBlockEntity(position) instanceof SmartInterfaceBlockEntity smart)) {
          return Optional.empty();
        }
        var binding = smart.read(owner, registration.interfaceType(index)).orElse(null);
        if (binding == null) {
          return Optional.empty();
        }
        interfaces.add(new SmartSnapshot(position, smart, binding));
      }
      if (part.rule().port() == null) {
        continue;
      }
      if (mode == CaptureMode.OUTPUTS
          && part.rule().port() != PortKind.ITEM_OUTPUT
          && part.rule().port() != PortKind.FLUID_OUTPUT
          && part.rule().port() != PortKind.CHEMICAL_OUTPUT
          || mode == CaptureMode.ENERGY_INPUTS && part.rule().port() != PortKind.ENERGY_INPUT
          || mode == CaptureMode.ENERGY_OUTPUTS && part.rule().port() != PortKind.ENERGY_OUTPUT
          || mode == CaptureMode.FLUID_INPUTS && part.rule().port() != PortKind.FLUID_INPUT
          || mode == CaptureMode.CHEMICAL_INPUTS && part.rule().port() != PortKind.CHEMICAL_INPUT) {
        continue;
      }
      var position = registration.pattern.position(index);
      if (!positions.add(position)
          || !level.hasChunkAt(position)
          || !(level.getBlockEntity(position) instanceof MachinePortBlockEntity port)
          || !port.schemaSupported()
          || port.kind() != part.rule().port()) {
        return Optional.empty();
      }
      if (port.resourceTransactionInProgress()) {
        return Optional.empty();
      }
      int tankCount = PortResourceAccess.require(port.kind().resource()).tanks().applyAsInt(port);
      for (int tank = 0; tank < tankCount; tank++) {
        ports.add(new RecipePortSnapshot(part.id(), position, port, level, part.selector(), tank, tankCount));
      }
    }
    ports.sort(Comparator.comparing(RecipePortSnapshot::logicalPartId).thenComparingInt(RecipePortSnapshot::tankIndex));
    var ticket = new RecipeExecutionTicket(owner, level, registration, ports, mode, interfaces);
    return ticket.matchesLive() ? Optional.of(ticket) : Optional.empty();
  }

  static List<RecipePortSnapshot> patternPorts(ServerLevel level, CaptureMode mode, PatternInputSource source) {
    var result = new ArrayList<RecipePortSnapshot>();
    var itemStacks = new ArrayList<ItemStack>();
    var amounts = new ArrayList<Long>();
    var itemKeys = new ArrayList<PatternResourceKey>();
    int fluidIndex = 0;
    int chemicalIndex = 0;
    for (var entry : source.resources().entrySet()) {
      var resource = entry.getKey().resource(level.registryAccess());
      switch (resource) {
        case NetworkPortResource.Item item -> {
          itemStacks.add(item.stack());
          amounts.add(entry.getValue());
          itemKeys.add(entry.getKey());
        }
        case NetworkPortResource.Fluid fluid -> result.add(
            new RecipePortSnapshot(
                source,
                PortKind.FLUID_INPUT,
                fluidIndex++,
                false,
                List.of(),
                List.of(),
                List.of(entry.getKey()),
                fluid.stack().copyWithAmount((int) Math.min(Integer.MAX_VALUE, entry.getValue())),
                ChemicalStackData.EMPTY));
        case NetworkPortResource.Chemical chemical -> result.add(
            new RecipePortSnapshot(
                source,
                PortKind.CHEMICAL_INPUT,
                chemicalIndex++,
                false,
                List.of(),
                List.of(),
                List.of(entry.getKey()),
                FluidStack.EMPTY,
                new ChemicalStackData(chemical.id(), entry.getValue())));
        default -> throw new IllegalArgumentException("No pattern input adapter for resource: " + resource.kind().id());
      }
    }
    if (mode == CaptureMode.ALL) {
      result.add(
          new RecipePortSnapshot(
              source,
              PortKind.ITEM_INPUT,
              0,
              false,
              itemStacks,
              amounts,
              itemKeys,
              FluidStack.EMPTY,
              ChemicalStackData.EMPTY));
      var stacks = new ArrayList<ItemStack>();
      var counts = new ArrayList<Long>();
      var inventory = source.provider().independentItems();
      for (int slot = 0; slot < inventory.getSlots(); slot++) {
        var stack = ItemStackSnapshots.detached(inventory.getStackInSlot(slot), level.registryAccess());
        stacks.add(stack);
        counts.add((long) stack.getCount());
      }
      result.add(
          new RecipePortSnapshot(
              source,
              PortKind.ITEM_INPUT,
              0,
              true,
              stacks,
              counts,
              List.of(),
              FluidStack.EMPTY,
              ChemicalStackData.EMPTY));
    }
    result.add(
        new RecipePortSnapshot(
            source,
            PortKind.FLUID_INPUT,
            0,
            true,
            List.of(),
            List.of(),
            List.of(),
            source.provider().independentFluid().getFluid(),
            ChemicalStackData.EMPTY));
    return result.stream()
        .filter(
            port -> mode == CaptureMode.ALL
                || mode == CaptureMode.FLUID_INPUTS && port.kind() == PortKind.FLUID_INPUT
                || mode == CaptureMode.CHEMICAL_INPUTS && port.kind() == PortKind.CHEMICAL_INPUT)
        .toList();
  }

}
