package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.component.PortResourceAccess;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.component.ResourceType;
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
    COMPONENTS,
    OUTPUTS,
    ENERGY_INPUTS,
    ENERGY_OUTPUTS,
    FLUID_INPUTS,
    CHEMICAL_INPUTS,
    CHEMICAL_OUTPUTS,
    TICK_ITEMS,
    TICK_FLUIDS,
    ENERGY_IO;

    boolean includes(PortKind kind) {
      return switch (this) {
        case ALL -> true;
        case COMPONENTS -> false;
        case OUTPUTS -> !kind.isInput() && kind != PortKind.ENERGY_OUTPUT;
        case ENERGY_INPUTS -> kind == PortKind.ENERGY_INPUT;
        case ENERGY_OUTPUTS -> kind == PortKind.ENERGY_OUTPUT;
        case ENERGY_IO -> kind == PortKind.ENERGY_INPUT || kind == PortKind.ENERGY_OUTPUT;
        case FLUID_INPUTS -> kind == PortKind.FLUID_INPUT;
        case CHEMICAL_INPUTS -> kind == PortKind.CHEMICAL_INPUT;
        case CHEMICAL_OUTPUTS -> kind == PortKind.CHEMICAL_OUTPUT;
        case TICK_ITEMS -> kind == PortKind.ITEM_INPUT || kind == PortKind.ITEM_OUTPUT;
        case TICK_FLUIDS -> kind == PortKind.FLUID_INPUT || kind == PortKind.FLUID_OUTPUT;
      };
    }
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
    var cache = owner instanceof FactoryControllerBlockEntity factory ? factory.tickResourceTickets() : null;
    if (cache != null && mode != CaptureMode.ALL) {
      cache.beginTick(level.getGameTime());
      var reused = cache.current(mode);
      if (reused != null) {
        return Optional.of(reused);
      }
    }
    var ports = new ArrayList<RecipePortSnapshot>();
    var interfaces = new ArrayList<SmartSnapshot>();
    var groups = new ArrayList<RecipeTicketProof.GroupStamp>();
    var positions = new HashSet<BlockPos>();
    var definition = registration.pattern.definition;
    List<Integer> indices = mode == CaptureMode.COMPONENTS ? List.of() : registration.pattern.recipePartIndices();
    for (int index : indices) {
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
      var kind = part.rule().port();
      boolean contents = mode.includes(kind);
      boolean groupOnly = (mode == CaptureMode.TICK_ITEMS || mode == CaptureMode.TICK_FLUIDS)
          && (kind == PortKind.ITEM_INPUT || kind == PortKind.FLUID_INPUT || kind == PortKind.CHEMICAL_INPUT);
      if (!contents && !groupOnly) {
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
      if (!contents || mode == CaptureMode.ALL) {
        groups.add(new RecipeTicketProof.GroupStamp(part.id(), port, port.inputGroup(), port.groupRevision()));
      }
      if (!contents) {
        continue;
      }
      int tankCount = PortResourceAccess.require(port.kind().resource()).tanks().applyAsInt(port);
      var images = cache != null && kind.resource() == ResourceType.ITEM ? cache.itemImages(registration, port) : null;
      for (int tank = 0; tank < tankCount; tank++) {
        ports.add(new RecipePortSnapshot(part.id(), position, port, level, part.selector(), tank, tankCount, images));
      }
    }
    ports.sort(Comparator.comparing(RecipePortSnapshot::logicalPartId).thenComparingInt(RecipePortSnapshot::tankIndex));
    var ticket = new RecipeExecutionTicket(owner, level, registration, ports, mode, interfaces, groups);
    if (!ticket.matchesLive()) {
      return Optional.empty();
    }
    if (cache != null && mode != CaptureMode.ALL) {
      cache.remember(mode, ticket);
    }
    return Optional.of(ticket);
  }

  static List<RecipePortSnapshot> patternPorts(ServerLevel level, CaptureMode mode, PatternInputSource source) {
    var result = new ArrayList<RecipePortSnapshot>();
    var itemStacks = new ArrayList<ItemStack>();
    var amounts = new ArrayList<Long>();
    var itemKeys = new ArrayList<PatternResourceKey>();
    int fluidIndex = 0;
    int chemicalIndex = 0;
    for (var entry : source.resources().entrySet()) {
      var kind = entry.getKey().kind();
      if (!(mode.includes(PortKind.ITEM_INPUT) && kind == PortKind.ITEM_INPUT.resource()
          || mode.includes(PortKind.FLUID_INPUT) && kind == PortKind.FLUID_INPUT.resource()
          || mode.includes(PortKind.CHEMICAL_INPUT) && kind == PortKind.CHEMICAL_INPUT.resource())) {
        continue;
      }
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
    if (mode.includes(PortKind.ITEM_INPUT)) {
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
      result.add(source.independentItemPort());
    }
    if (mode.includes(PortKind.FLUID_INPUT)) {
      result.add(
          new RecipePortSnapshot(
              source,
              PortKind.FLUID_INPUT,
              0,
              true,
              List.of(),
              List.of(),
              List.of(),
              source.capturedIndependentFluid(),
              ChemicalStackData.EMPTY));
    }
    return List.copyOf(result);
  }

}
