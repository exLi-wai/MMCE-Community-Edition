package com.lw.modularmachine.integration.thaumaturge;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import com.lw.modularmachine.registry.MachineRegistries;
import com.traverse.appliedthaumaturge.me.key.EssentiaKey;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MeEssentiaHatch extends EssentiaHatch implements IInWorldGridNodeHost, IActionHost {
  private static final IGridNodeListener<MeEssentiaHatch> NODE_LISTENER = new IGridNodeListener<>() {
    @Override
    public void onSaveChanges(MeEssentiaHatch hatch, IGridNode node) {
      hatch.setChanged();
    }
  };
  private final boolean direct;
  private final IActionSource source = IActionSource.ofMachine(this);
  private IManagedGridNode node;
  private CompoundTag retainedNode = new CompoundTag();
  private MEStorage refundTarget;
  private long compensationReserved;
  private long lifecycle;
  private int outputCursor;

  public MeEssentiaHatch(BlockPos position, BlockState state) {
    super(MachineRegistries.ME_ESSENTIA_ENTITY.get(), position, state,
        !state.is(MachineRegistries.ME_ESSENTIA_OUTPUT.get()));
    direct = state.is(MachineRegistries.ME_ESSENTIA_STOCK.get());
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) {
      long expected = ++lifecycle;
      GridHelper.onFirstTick(this, ignored -> {
        if (expected == lifecycle && live() && node == null) {
          node = GridHelper.createManagedNode(this, NODE_LISTENER).setTagName("node").setInWorldNode(true)
              .setFlags(GridFlags.REQUIRE_CHANNEL).setExposedOnSides(EnumSet.allOf(Direction.class))
              .setIdlePowerUsage(1).setVisualRepresentation(getBlockState().getBlock());
          node.loadFromNBT(retainedNode);
          node.create(level, worldPosition);
        }
      });
    }
  }

  @Override
  public void onChunkUnloaded() {
    closeNode();
    super.onChunkUnloaded();
  }

  @Override
  public void setRemoved() {
    closeNode();
    super.setRemoved();
  }

  private void closeNode() {
    lifecycle++;
    if (node != null) {
      retainedNode = new CompoundTag();
      node.saveToNBT(retainedNode);
      node.destroy();
      node = null;
    }
    refundTarget = null;
  }

  @Override
  public IGridNode getGridNode(Direction side) {
    return live() && node != null ? node.getNode() : null;
  }

  @Override
  public AECableType getCableConnectionType(Direction side) {
    return AECableType.SMART;
  }

  @Override
  public IGridNode getActionableNode() {
    return getGridNode(null);
  }

  private MEStorage inventory() {
    var grid = live() && node != null && node.isActive() ? node.getGrid() : null;
    return grid == null ? null : grid.getStorageService().getInventory();
  }

  @Override
  public Object resourceAddress() {
    return direct ? inventory() : super.resourceAddress();
  }

  @Override
  public long available(ResourceLocation id) {
    if (!direct) {
      return super.available(id);
    }
    var inventory = inventory();
    var key = live() ? toKey(id) : null;
    long space = totalAvailable();
    return transferring || inventory == null || key == null || space <= 0 ? 0 : Math
        .clamp(inventory.extract(key, space, Actionable.SIMULATE, source), 0, space);
  }

  @Override
  public long totalAvailable() {
    if (!direct) {
      return Long.MAX_VALUE;
    }
    return stock.total() > 0 && refundTarget != inventory() ? 0 : stock.space() - compensationReserved;
  }

  @Override
  public boolean watchInput(ReplenishmentProof proof) {
    return !direct && super.watchInput(proof);
  }

  @Override
  void recoverContents() {
    if (compensationReserved == 0) {
      super.recoverContents();
      refundTarget = null;
    }
  }

  @Override
  protected boolean hasRequestSource() {
    return !direct && inventory() != null;
  }

  @Override
  public long requestSupply(ResourceLocation id, long maximum) {
    var inventory = input && !direct && mutable() ? inventory() : null;
    var key = inventory == null ? null : toKey(id);
    return key == null ? 0 : Math.clamp(inventory.extract(key, maximum, Actionable.SIMULATE, source), 0, maximum);
  }

  @Override
  public long request(ResourceLocation key, long additional, ReplenishmentProof publisher) {
    return direct || inventory() == null ? 0 : super.request(key, additional, publisher);
  }

  @Override
  public RecipeResourceParticipant acquire(ResourceLocation id, long delta) {
    if (!direct) {
      return super.acquire(id, delta);
    }
    var inventory = inventory();
    var key = live() ? toKey(id) : null;
    long needed = -delta;
    if (!mutable()
        || inventory == null
        || key == null
        || delta >= 0
        || needed > totalAvailable()
        || stock.total() != 0 && refundTarget != inventory) {
      throw new IllegalStateException("ME essentia inventory cannot reserve this payment");
    }
    transferring = true;
    try {
      long extracted = inventory.extract(key, needed, Actionable.MODULATE, source);
      if (extracted != needed) {
        if (extracted > 0) {
          refund(inventory, key, id, extracted);
        }
        throw new IllegalStateException("ME essentia extraction did not match the reservation");
      }
      compensationReserved += extracted;
    } finally {
      transferring = false;
    }
    return commit -> {
      try {
        if (!commit) {
          transferring = true;
          refund(inventory, key, id, needed);
        }
      } finally {
        transferring = false;
        compensationReserved -= needed;
      }
    };
  }

  private void refund(MEStorage inventory, EssentiaKey key, ResourceLocation id, long amount) {
    long remainder = amount;
    try {
      if (inventory() == inventory) {
        remainder -= Math.clamp(inventory.insert(key, amount, Actionable.MODULATE, source), 0, amount);
      }
    } finally {
      if (remainder > 0) {
        if (stock.move(id, remainder, false) != remainder) {
          throw new IllegalStateException("ME essentia compensation reservation overflow");
        }
        refundTarget = inventory;
        setChanged();
      }
    }
  }

  @Override
  public void tickHatch() {
    if (!mutable()
        || compensationReserved != 0
        || Math.floorMod(level.getGameTime() + worldPosition.asLong(), 20) != 0) {
      return;
    }
    var inventory = inventory();
    if (inventory == null || direct && refundTarget != inventory) {
      return;
    }
    boolean extracting = input && !direct;
    var keys = stock.keys();
    var id = extracting ? requested() : nextOutputKey(keys);
    if (id == null) {
      return;
    }
    long maximum = Math.min(256, extracting ? deficit(id) : stock.amount(id));
    var key = toKey(id);
    if (key != null && maximum > 0) {
      transferring = true;
      try {
        long moved = extracting ? inventory.extract(key, maximum, Actionable.MODULATE, source) : inventory
            .insert(key, maximum, Actionable.MODULATE, source);
        long delta = extracting ? moved : -moved;
        if (moved < 0 || moved > maximum || stock.move(id, delta, false) != delta) {
          throw new IllegalStateException("ME essentia reported an invalid transfer amount");
        }
        if (moved > 0) {
          if (direct && stock.total() == 0) {
            refundTarget = null;
          }
          setChanged();
        }
      } finally {
        transferring = false;
      }
    }
    if (extracting) {
      rotateDemand(id);
    }
  }

  private ResourceLocation nextOutputKey(java.util.List<ResourceLocation> keys) {
    return keys.isEmpty() ? null : keys.get(Math.floorMod(outputCursor++, keys.size()));
  }

  private EssentiaKey toKey(ResourceLocation id) {
    var aspect = aspect(id);
    return aspect == null ? null : EssentiaKey.of(aspect);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    var savedNode = node == null ? retainedNode.copy() : new CompoundTag();
    if (node != null) {
      node.saveToNBT(savedNode);
    }
    tag.put("network", savedNode);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel && (node != null || compensationReserved != 0)) {
      throw new IllegalStateException("Cannot replace a live ME essentia node");
    }
    super.loadAdditional(tag, registries);
    retainedNode = tag.getCompound("network").copy();
  }
}
