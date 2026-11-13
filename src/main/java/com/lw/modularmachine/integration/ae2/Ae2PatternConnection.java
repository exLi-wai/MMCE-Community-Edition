package com.lw.modularmachine.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageHelper;
import appeng.api.util.AECableType;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.NetworkPortService;
import com.lw.modularmachine.machine.network.pattern.PatternContainerRef;
import com.lw.modularmachine.machine.network.pattern.PatternDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternJobs;
import com.lw.modularmachine.machine.network.pattern.PatternNetworkPlatform;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import com.lw.modularmachine.machine.network.pattern.PatternRecipeDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.machine.network.pattern.PatternResourceLedger.JobStatus;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal real provider node and exact CPU delivery/partial return boundary.
 */
@ApiStatus.Internal
final class Ae2PatternConnection implements PatternNetworkPlatform.Connection, ICraftingProvider, IInWorldGridNodeHost, IActionHost, NetworkPortService.Work {
  private static final String NODE_TAG = "node";
  private static final String OWNER_TAG = "owner_id";
  private static final IGridNodeListener<Ae2PatternConnection> LISTENER = new IGridNodeListener<>() {
    @Override
    public void onSaveChanges(Ae2PatternConnection owner, IGridNode node) {
      owner.provider.setChanged();
    }

    @Override
    public void onStateChanged(Ae2PatternConnection owner, IGridNode node, State state) {
      owner.provider.setChanged();
    }
  };
  private final PatternProviderBlockEntity provider;
  private final Function<ChemicalStackData, AEKey> chemicalKeys;
  private final Ae2PatternInputs decoder;
  private final IActionSource source = IActionSource.ofMachine(this);
  private final Map<IPatternDetails, Group> groups = new LinkedHashMap<>();
  private final Map<PatternDefinition, Integer> rotations = new HashMap<>();
  private IManagedGridNode node;
  private CompoundTag retained = new CompoundTag();
  private int owningPlayerId = -1;
  private long lifecycle;
  private boolean loaded;
  private boolean patternsDirty = true;
  private boolean transferring;
  private Iterator<PatternProviderState.NetworkShare> shares = List.<PatternProviderState.NetworkShare>of().iterator();
  private PatternProviderState.NetworkShare share;
  private Iterator<PatternResourceKey> keys = List.<PatternResourceKey>of().iterator();

  private static final class Group {
    private final IPatternDetails pattern;
    private final Ae2PatternInputs.Decoded decoded;
    private final PatternRecipeDefinition declaration;
    private final List<PatternContainerRef> addresses = new ArrayList<>();
    private boolean ambiguous;

    private Group(IPatternDetails pattern, Ae2PatternInputs.Decoded decoded) {
      this.pattern = pattern;
      this.decoded = decoded;
      declaration = new PatternRecipeDefinition(decoded.definition(), decoded.inputs(), decoded.outputs());
    }
  }

  Ae2PatternConnection(PatternProviderBlockEntity provider, Function<ChemicalStackData, AEKey> chemicalKeys, Function<AEKey, NetworkPortResource> chemicalDecoder) {
    this.provider = provider;
    this.chemicalKeys = chemicalKeys;
    decoder = new Ae2PatternInputs(chemicalDecoder);
  }

  @Override
  public void onLoad() {
    if (!(provider.getLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Pattern provider load");
    loaded = true;
    patternsDirty = true;
    Ae2PatternJobs.ensure(level);
    NetworkPortService.add(this);
    long expected = ++lifecycle;
    GridHelper.onFirstTick(provider, ignored -> {
      if (expected == lifecycle && current() && node == null) {
        node = GridHelper.createManagedNode(this, LISTENER).setTagName(NODE_TAG).setInWorldNode(true)
            .setFlags(GridFlags.REQUIRE_CHANNEL).setExposedOnSides(EnumSet.allOf(Direction.class))
            .setIdlePowerUsage(1.0D).setVisualRepresentation(provider.getBlockState().getBlock())
            .addService(ICraftingProvider.class, this);
        node.loadFromNBT(retained.copy());
        node.create(level, provider.getBlockPos());
        if (owningPlayerId >= 0) {
          node.setOwningPlayerId(owningPlayerId);
        }
        patternsChanged();
      }
    });
  }

  @Override
  public void destroy() {
    if (provider.getLevel() instanceof ServerLevel level) {
      ServerThreadGuard.require(level, "Pattern provider unload");
    }
    loaded = false;
    lifecycle++;
    NetworkPortService.remove(this);
    shares = List.<PatternProviderState.NetworkShare>of().iterator();
    keys = List.<PatternResourceKey>of().iterator();
    share = null;
    groups.clear();
    if (node != null) {
      retained = new CompoundTag();
      node.saveToNBT(retained);
      if (node.getNode() != null) {
        owningPlayerId = node.getNode().getOwningPlayerId();
      }
      node.destroy();
      node = null;
    }
  }

  @Override
  public void load(CompoundTag tag, HolderLookup.Provider registries) {
    if (node != null) {
      throw new IllegalStateException("Cannot replace a live provider node");
    }
    retained = tag.copy();
    owningPlayerId = tag.contains(OWNER_TAG, Tag.TAG_INT) ? tag.getInt(OWNER_TAG) : -1;
    patternsDirty = true;
  }

  @Override
  public void save(CompoundTag tag, HolderLookup.Provider registries) {
    if (node != null) {
      node.saveToNBT(tag);
    } else if (retained.contains(NODE_TAG, Tag.TAG_COMPOUND)) {
      tag.put(NODE_TAG, retained.getCompound(NODE_TAG).copy());
    }
    int owner = node != null && node.getNode() != null ? node.getNode().getOwningPlayerId() : owningPlayerId;
    if (owner >= 0) {
      tag.putInt(OWNER_TAG, owner);
    }
  }

  @Override
  public void setOwningPlayer(Player player) {
    if (player instanceof ServerPlayer serverPlayer) {
      ServerThreadGuard.require(serverPlayer.serverLevel(), "Pattern provider owner");
      owningPlayerId = IPlayerRegistry.getPlayerId(serverPlayer);
      if (node != null) {
        node.setOwningPlayerId(owningPlayerId);
      }
      provider.setChanged();
    }
  }

  @Override
  public MinecraftServer server() {
    return provider.getLevel() instanceof ServerLevel level ? level.getServer() : null;
  }

  @Override
  public boolean current() {
    if (!(provider.getLevel() instanceof ServerLevel level)) {
      return false;
    }
    ServerThreadGuard.require(level, "Pattern provider access");

    return loaded
        && !provider.isRemoved()
        && (provider.state().inTransaction() || provider.schemaSupported())
        && level.hasChunkAt(provider.getBlockPos())
        && level.getBlockEntity(provider.getBlockPos()) == provider;
  }

  @Override
  public boolean active() {
    return current() && node != null && node.isActive();
  }

  @Override
  public void patternsChanged() {
    patternsDirty = true;
    if (node != null && current()) {
      ICraftingProvider.requestUpdate(node);
    }
  }

  @Override
  public Optional<PatternRecipeDefinition> pattern(ItemStack stack) {
    if (!(provider.getLevel() instanceof ServerLevel level) || stack.isEmpty()) {
      return Optional.empty();
    }
    try {
      return decoder.decode(PatternDetailsHelper.decodePattern(stack.copyWithCount(1), level), level)
          .map(value -> new PatternRecipeDefinition(value.definition(), value.inputs(), value.outputs()));
    } catch (RuntimeException invalid) {
      return Optional.empty();
    }
  }

  private void refreshPatterns() {
    if (!patternsDirty || !current() || !provider.canMutate()) {
      return;
    }
    groups.clear();
    var level = (ServerLevel) provider.getLevel();
    for (int slot = 0; slot < PatternProviderState.SLOT_COUNT; slot++) {
      var metadata = provider.state().currentMetadata(slot).orElse(null);
      if (metadata == null || metadata.retired()) {
        continue;
      }
      IPatternDetails pattern;
      try {
        pattern = PatternDetailsHelper.decodePattern(metadata.pattern().stack(level.registryAccess()), level);
      } catch (RuntimeException invalid) {
        continue;
      }
      var decoded = decoder.decode(pattern, level).orElse(null);
      if (decoded == null || !decoded.definition().equals(metadata.pattern())) {
        continue;
      }
      var group = groups.computeIfAbsent(pattern, ignored -> new Group(pattern, decoded));
      group.ambiguous |= !group.decoded.definition().equals(decoded.definition());
      group.addresses.add(metadata.address());
    }
    rotations.keySet().retainAll(groups.values().stream().map(group -> group.decoded.definition()).toList());
    patternsDirty = false;
  }

  @Override
  public List<IPatternDetails> getAvailablePatterns() {
    if (!current() || !provider.canMutate()) {
      return List.of();
    }
    refreshPatterns();

    return groups.values().stream()
        .filter(group -> !group.ambiguous && PatternProductionService.isCompatible(provider, group.declaration))
        .map(group -> group.pattern).toList();
  }

  @Override
  public boolean isBusy() {
    return !active() || transferring || !provider.canMutate();
  }

  @Override
  public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
    if (isBusy()) {
      return false;
    }
    var context = Ae2PatternJobs.context(this);
    if (context == null) {
      return false;
    }
    refreshPatterns();
    var group = groups.get(pattern);
    if (group == null || group.ambiguous || !PatternProductionService.isCompatible(provider, group.declaration)) {
      return false;
    }
    var batch = decoder.prepareDelivery(group.decoded, pattern, inputs, (ServerLevel) provider.getLevel()).orElse(null);
    if (batch == null) {
      return false;
    }
    int first = Math.floorMod(rotations.getOrDefault(group.decoded.definition(), 0), group.addresses.size());
    for (int offset = 0; offset < group.addresses.size(); offset++) {
      int index = (first + offset) % group.addresses.size();
      var address = group.addresses.get(index);
      var state = provider.state();
      var metadata = state.currentMetadata(address.slot()).orElse(null);
      if (metadata == null || !metadata.address().equals(address) || !context.valid()) {
        continue;
      }
      boolean received;
      try (var lease = state.acquire(state.instanceId(), state.revision())) {
        received = lease.receive(address, context.job(), batch);
        lease.resolve(received);
      } catch (RuntimeException rejected) {
        // Nothing external was extracted here; AE retains its original holders.
        return false;
      }
      if (received) {
        context.accepted();
        rotations.put(group.decoded.definition(), (index + 1) % group.addresses.size());
        publishCommitted();
        return true;
      }
    }

    return false;
  }

  private void publishCommitted() {
    try {
      provider.publishResourceChange();
    } catch (RuntimeException notification) {
      // The CPU must never refund input already owned by a committed receipt.
      provider.setChanged();
      ModularMachine.LOGGER
          .error("Pattern provider notification failed after commit at {}", provider.getBlockPos(), notification);
    }
  }

  @Override
  public void networkStep() {
    if (!current() || !provider.canMutate() || transferring) {
      return;
    }
    var state = provider.state();
    if (!keys.hasNext()) {
      if (!shares.hasNext()) {
        shares = state.networkCursor();
      }
      if (!shares.hasNext()) {
        return;
      }
      share = shares.next();
      keys = share.resources().keySet().iterator();
      if (!state.contains(share.address())) {
        keys = List.<PatternResourceKey>of().iterator();
        return;
      }
      if (share.job() != null) {
        var job = PatternJobs.get((ServerLevel) provider.getLevel()).job(share.job());
        if (job != null && job.status() != JobStatus.ACTIVE && share.status() == JobStatus.ACTIVE) {
          boolean changed;
          try (var lease = state.acquire(state.instanceId(), state.revision())) {
            changed = lease.finishJob(share.address(), share.job(), job.status() == JobStatus.COMPLETED);
            lease.resolve(changed);
          }
          if (changed) {
            publishCommitted();
          }
          return;
        }
      }
    }
    if (!keys.hasNext() || !active() || !state.contains(share.address())) {
      return;
    }
    var key = keys.next();
    if (state.returnable(share.address(), share.job(), key) <= 0) {
      return;
    }
    var aeKey = key(key);
    var grid = node.getGrid();
    if (aeKey == null || grid == null) {
      return;
    }
    boolean changed = false;
    try (var lease = state.acquire(state.instanceId(), state.revision())) {
      long maximum = lease.returnable(share.address(), share.job(), key);
      if (maximum <= 0) {
        lease.resolve(false);
        return;
      }
      transferring = true;
      try {
        long accepted = StorageHelper
            .poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), aeKey, maximum, source);
        if (!lease.settleReturn(share.address(), share.job(), key, accepted)) {
          throw new IllegalStateException("AE returned an invalid accepted amount");
        }
        changed = accepted > 0;
        lease.resolve(changed);
      } finally {
        transferring = false;
      }
    }
    if (changed) {
      publishCommitted();
    }
  }

  private AEKey key(PatternResourceKey key) {
    return switch (key.resource(provider.getLevel().registryAccess())) {
      case NetworkPortResource.Item item -> AEItemKey.of(item.stack());
      case NetworkPortResource.Fluid fluid -> AEFluidKey.of(fluid.stack());
      case NetworkPortResource.Chemical chemical ->
        chemicalKeys == null ? null : chemicalKeys.apply(new ChemicalStackData(chemical.id(), 1));
    };
  }

  @Override
  public IGridNode getGridNode(Direction side) {
    return node == null || !current() ? null : node.getNode();
  }

  @Override
  public AECableType getCableConnectionType(Direction side) {
    return AECableType.SMART;
  }

  @Override
  public IGridNode getActionableNode() {
    return getGridNode(null);
  }
}
