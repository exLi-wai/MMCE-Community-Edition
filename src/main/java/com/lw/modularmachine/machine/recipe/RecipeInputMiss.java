package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Physical inputs that excluded every allowed recipe in every group of one
 * completed factory search.
 */
@ApiStatus.Internal
public final class RecipeInputMiss {
  public enum Status {
    PENDING,
    UNCHANGED,
    STALE
  }
  private static final int WORK_PER_VISIT = 32;
  private static final int CONTENT_INTERVAL = 80;
  private final FactoryControllerBlockEntity factory;
  private final ServerLevel level;
  private final String threadId;
  private final RecipeCatalog catalog;
  private final StructureService.Registration registration;
  private final long structureRevision;
  private final long scriptRevision;
  private final long configurationRevision;
  private final List<RecipePortSnapshot> inputs = new LinkedList<>();
  private final List<RecipeTicketProof.GroupStamp> groups;
  private final Map<BlockPos, RecipePortSnapshot> inputByPosition = new TreeMap<>();
  private final Map<BlockPos, RecipeTicketProof.GroupStamp> chemicalGroups = new TreeMap<>();
  private long nextCheck = Long.MIN_VALUE;
  private boolean scanning;
  private Iterator<RecipePortSnapshot> remainingInputs;
  private RecipePortSnapshot currentInput;
  private int slotIndex;
  private int groupIndex;

  private RecipeInputMiss(FactoryControllerBlockEntity factory, String threadId, RecipeCatalog catalog, RecipeTicketProof proof) {
    this.factory = factory;
    level = proof.level;
    this.threadId = threadId;
    this.catalog = catalog;
    registration = proof.registration;
    structureRevision = proof.structureRevision;
    scriptRevision = proof.scriptRevision;
    configurationRevision = factory.threadConfiguration().revision();
    groups = proof.groups;
  }

  static Capture begin(RecipeTicketProof proof, String threadId, RecipeCatalog catalog) {
    if (!(proof.owner instanceof FactoryControllerBlockEntity factory)
        || threadId == null
        || !proof.patternSources.isEmpty()
        || !PatternProductionService.hasObservedNoGateways(factory)) {
      return null;
    }
    return new Capture(new RecipeInputMiss(factory, threadId, catalog, proof), proof.ports);
  }

  public static final class Capture {
    private final RecipeInputMiss miss;
    private final List<RecipePortSnapshot> ports;
    private int portIndex;
    private int groupIndex;

    private Capture(RecipeInputMiss miss, List<RecipePortSnapshot> ports) {
      this.miss = miss;
      this.ports = ports;
    }

    public int advance(int budget, long deadline) {
      int used = 0;
      while (used < budget && (deadline == Long.MAX_VALUE || System.nanoTime() - deadline < 0)) {
        if (portIndex < ports.size()) {
          var port = ports.get(portIndex++);
          if (port.kind() == PortKind.ITEM_INPUT || port.kind() == PortKind.FLUID_INPUT) {
            miss.inputs.add(port);
            miss.inputByPosition.putIfAbsent(port.position(), port);
          }
        } else if (groupIndex < miss.groups.size()) {
          var group = miss.groups.get(groupIndex++);
          if (group.port().kind() == PortKind.CHEMICAL_INPUT) {
            miss.chemicalGroups.put(group.port().getBlockPos(), group);
          }
        } else {
          break;
        }
        used++;
      }
      return used;
    }

    public RecipeInputMiss result() {
      return portIndex == ports.size() && groupIndex == miss.groups.size() ? miss : null;
    }
  }

  public boolean portChanged(MachinePortBlockEntity port) {
    var input = inputByPosition.get(port.getBlockPos());
    if (input != null) {
      return input.identity() != port
          || port.resourceRevision() != input.revision()
          || port.inputGroup() != input.inputGroup();
    }
    var group = chemicalGroups.get(port.getBlockPos());
    return group != null && (group.port() != port || group.revision() != port.groupRevision());
  }

  public long nextCheck() {
    return nextCheck;
  }

  public Status advance(RecipeCatalog base, long now, int budget, long deadline) {
    if (!metadataCurrent(base)) {
      return Status.STALE;
    }
    if (!scanning && now < nextCheck) {
      return Status.UNCHANGED;
    }
    int remaining = Math.min(budget, WORK_PER_VISIT);
    while (remaining-- > 0 && (deadline == Long.MAX_VALUE || System.nanoTime() - deadline < 0)) {
      if (!scanning) {
        if (!factory.revalidateStructure(registration)
            || !factory.upgradeRestrictionsCurrent()
            || !PatternProductionService.hasObservedNoGateways(factory)
            || MachineRecipeService.catalogForFactory(factory, threadId, base).orElse(null) != catalog) {
          return Status.STALE;
        }
        scanning = true;
        nextCheck = now + CONTENT_INTERVAL;
        remainingInputs = inputs.iterator();
        currentInput = null;
        slotIndex = 0;
        groupIndex = 0;
      } else if (currentInput != null || remainingInputs.hasNext()) {
        if (currentInput == null) {
          currentInput = remainingInputs.next();
        }
        if (currentInput.kind() == PortKind.ITEM_INPUT) {
          if (!currentInput.itemInputSlotCurrent(level, slotIndex)) {
            return Status.STALE;
          }
          if (++slotIndex < currentInput.itemSlotCount()) {
            continue;
          }
          slotIndex = 0;
        } else if (!currentInput.matches(level)) {
          return Status.STALE;
        }
        currentInput = null;
      } else if (groupIndex < groups.size()) {
        if (!groups.get(groupIndex++).current(level)) {
          return Status.STALE;
        }
      } else {
        scanning = false;
        return metadataCurrent(base) ? Status.UNCHANGED : Status.STALE;
      }
    }
    return metadataCurrent(base) ? Status.PENDING : Status.STALE;
  }

  private boolean metadataCurrent(RecipeCatalog base) {
    return factory.getLevel() == level
        && !factory.isRemoved()
        && catalog.root() == base
        && factory.structureRegistration() == registration
        && registration.verifier.revision() == structureRevision
        && factory.threadConfiguration().revision() == configurationRevision
        && MachineControllerScriptDataService.revision(factory) == scriptRevision
        && catalog.isCurrent();
  }
}
