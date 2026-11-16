package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.component.PortResourceAccess;
import com.lw.modularmachine.machine.network.pattern.PatternContainerRef;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Captured physical resource identity, detached values and content/version
 * fences; never a write lease.
 */
@ApiStatus.Internal
public final class RecipePortSnapshot {
  private final String partId;
  private final String logicalPartId;
  private final int tankIndex;
  private final int tankCount;
  private final boolean bulkItems;
  private final BlockPos position;
  private final MachinePortBlockEntity identity;
  private final ServerLevel level;
  private final PortKind kind;
  private final long revision;
  private final List<ItemStack> items;
  private final List<Long> itemAmounts;
  private final PatternInputSource pattern;
  private final List<PatternResourceKey> patternKeys;
  private final boolean independent;
  private final List<Integer> limits;
  private final FluidStack fluid;
  private final ChemicalStackData chemical;
  private final long energy;
  private final long energyExtractable;
  private final long energyReceivable;
  private final long capacity;
  private final long transferLimit;
  private final String selector;
  private final int inputGroup;

  RecipePortSnapshot(String logicalPartId, BlockPos position, MachinePortBlockEntity port, ServerLevel level, String selector, int tankIndex, int tankCount) {
    this.logicalPartId = logicalPartId;
    pattern = null;
    patternKeys = List.of();
    independent = false;
    // Encode every transient address, including single tanks and item buses.
    // Length framing makes arbitrary user IDs unambiguous; only logical IDs
    // persist.
    partId = logicalPartId.length() + ":" + logicalPartId + ":" + tankIndex;
    this.tankIndex = tankIndex;
    this.tankCount = tankCount;
    bulkItems = port.bulkItems();
    this.selector = ResourceSelector.require(selector);
    inputGroup = port.inputGroup();
    this.position = position.immutable();
    identity = port;
    this.level = level;
    kind = port.kind();
    revision = port.resourceRevision();
    var stacks = new ArrayList<ItemStack>();
    var slotLimits = new ArrayList<Integer>();
    if (kind.resource() == ResourceType.ITEM) {
      for (int slot = 0; slot < port.inventory().getSlots(); slot++) {
        stacks.add(ItemStackSnapshots.detached(port.inventory().getStackInSlot(slot), level.registryAccess()));
        slotLimits.add(port.inventory().getSlotLimit(slot));
      }
    }
    items = List.copyOf(stacks);
    itemAmounts = stacks.stream().map(stack -> (long) stack.getCount()).toList();
    limits = List.copyOf(slotLimits);
    fluid = kind.resource() == ResourceType.FLUID ? port.fluidTank(tankIndex).getFluid().copy() : FluidStack.EMPTY;
    chemical = kind.resource() == ResourceType.CHEMICAL ? port.chemicalTank(tankIndex)
        .getStack() : ChemicalStackData.EMPTY;
    energy = kind.resource() == ResourceType.ENERGY ? port.energy().stored() : 0;
    energyExtractable = kind == PortKind.ENERGY_INPUT ? port.energy().extractableNow() : 0;
    energyReceivable = kind == PortKind.ENERGY_OUTPUT ? port.energy().receivableNow() : 0;
    capacity = PortResourceAccess.require(kind.resource()).capacity().read(port, tankIndex);
    transferLimit = kind.resource() == ResourceType.ENERGY ? port.energy().transferLimit() : 0;
  }

  RecipePortSnapshot(PatternInputSource source, PortKind kind, int index, boolean independent, List<ItemStack> items, List<Long> amounts, List<PatternResourceKey> keys, FluidStack fluid, ChemicalStackData chemical) {
    pattern = source;
    this.independent = independent;
    patternKeys = List.copyOf(keys);
    identity = null;
    level = (ServerLevel) source.provider().getLevel();
    position = source.provider().getBlockPos();
    this.kind = kind;
    selector = source.selector();
    inputGroup = InputGroup.SHARED;
    revision = source.revision();
    logicalPartId = "pattern:" + source.provider().providerId();
    String scope = independent ? "independent" : source.container() + ":" + source.job();
    partId = logicalPartId + ":" + scope + ":" + kind + ":" + index;
    tankIndex = index;
    tankCount = 1;
    bulkItems = true;
    this.items = List.copyOf(items);
    itemAmounts = List.copyOf(amounts);
    limits = items.stream().map(stack -> Integer.MAX_VALUE).toList();
    this.fluid = fluid.copy();
    this.chemical = chemical;
    energy = 0;
    energyExtractable = 0;
    energyReceivable = 0;
    capacity = kind == PortKind.CHEMICAL_INPUT ? Long.MAX_VALUE : Integer.MAX_VALUE;
    transferLimit = 0;
  }

  public boolean patternInput() {
    return pattern != null;
  }
  public PatternInputSource patternSource() {
    return pattern;
  }
  public boolean independentPatternStorage() {
    return independent;
  }
  public PatternResourceKey patternKey(int slot) {
    return patternKeys.get(slot);
  }
  public long itemAmount(int slot) {
    return itemAmounts.get(slot);
  }
  public long fluidAmount() {
    return patternInput() && !independentPatternStorage() && kind == PortKind.FLUID_INPUT ? pattern.resources()
        .getOrDefault(patternKey(0), 0L) : fluid.getAmount();
  }
  public Object physicalIdentity() {
    if (!patternInput()) {
      return new PhysicalTank(identity, tankIndex);
    }

    return new PatternPhysical(
        pattern.providerId(),
        independent ? null : pattern.container(),
        independent ? null : pattern.job(),
        kind,
        independent ? null : patternKeys.isEmpty() ? null : patternKeys.getFirst());
  }
  private record PatternPhysical(
      UUID provider,
      PatternContainerRef container,
      UUID job,
      PortKind kind,
      PatternResourceKey resource) {
  }

  private record PhysicalTank(MachinePortBlockEntity port, int tank) {
  }

  public String partId() {
    return partId;
  }

  public String logicalPartId() {
    return logicalPartId;
  }

  public int tankIndex() {
    return tankIndex;
  }

  public boolean bulkItems() {
    return bulkItems;
  }

  public String selector() {
    return selector;
  }

  public int inputGroup() {
    return inputGroup;
  }

  public BlockPos position() {
    return position;
  }

  public PortKind kind() {
    return kind;
  }

  public long revision() {
    return revision;
  }

  public List<ItemStack> items() {
    ServerThreadGuard.require(level, "Recipe resource access");
    return items.stream().map(stack -> ItemStackSnapshots.detached(stack, level.registryAccess())).toList();
  }

  public List<Integer> slotLimits() {
    return limits;
  }

  public FluidStack fluid() {
    return fluid.copy();
  }

  public ChemicalStackData chemical() {
    return chemical;
  }

  public long energy() {
    return energy;
  }

  public long energyExtractable() {
    return energyExtractable;
  }

  public long energyReceivable() {
    return energyReceivable;
  }

  public long capacity() {
    return capacity;
  }

  public long transferLimit() {
    return transferLimit;
  }

  MachinePortBlockEntity identity() {
    return identity;
  }

  boolean sameLiveStamp(ServerLevel level) {
    if (pattern != null) {
      return pattern.current();
    }
    return level.hasChunkAt(position)
        && !identity.isRemoved()
        && identity.getLevel() == level
        && level.getBlockEntity(position) == identity
        && identity.schemaSupported()
        && identity.kind() == kind
        && identity.bulkItems() == bulkItems
        && identity.resourceRevision() == revision
        && identity.inputGroup() == inputGroup;
  }

  boolean matches(ServerLevel level) {
    if (pattern != null) {
      return pattern.current();
    }
    if (!sameLiveStamp(level) || identity.resourceTransactionInProgress()) {
      return false;
    }
    if (kind.resource() == ResourceType.ITEM) {
      if (identity.inventory().getSlots() != items.size()) {
        return false;
      }
      for (int slot = 0; slot < items.size(); slot++) {
        if (identity.inventory().getSlotLimit(slot) != limits.get(slot)
            || !ItemStack.matches(items.get(slot), identity.inventory().getStackInSlot(slot))) {
          return false;
        }
      }
    }
    if (kind.resource() == ResourceType.FLUID) {
      if (identity.fluidTankCount() != tankCount) {
        return false;
      }
      var actual = identity.fluidTank(tankIndex).getFluid();
      if (identity.fluidTank(tankIndex).getCapacity() != capacity
          || fluid.getAmount() != actual.getAmount()
          || !FluidStack.isSameFluidSameComponents(fluid, actual)) {
        return false;
      }
    }
    if (kind.resource() == ResourceType.CHEMICAL
        && (identity.chemicalTankCount() != tankCount
            || !chemical.equals(identity.chemicalTank(tankIndex).getStack())
            || capacity != identity.chemicalTank(tankIndex).getCapacity())) {
      return false;
    }
    return kind.resource() != ResourceType.ENERGY
        || energy == identity.energy().stored()
            && capacity == identity.energy().capacity()
            && transferLimit == identity.energy().transferLimit();
  }
}
