package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.network.pattern.PatternProviderReference;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal reference-only machine component. It owns no AE node or physical
 * cache.
 */
@ApiStatus.Internal
public final class PatternMirrorBlockEntity extends BlockEntity implements MachineColorable {
  public static final String DATA_TAG = "pattern_mirror";
  private final UUID instance = UUID.randomUUID();
  private PatternProviderReference target;
  private long bindingRevision;
  private Tag opaque;
  private int machineColor = MachineConfig.defaultCasingColor();
  private boolean machineColorAssigned;

  public PatternMirrorBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.PATTERN_MIRROR_ENTITY.get(), position, state);
  }
  public UUID instanceId() {
    return instance;
  }
  public long bindingRevision() {
    return bindingRevision;
  }
  public Optional<PatternProviderReference> binding() {
    return Optional.ofNullable(target);
  }
  public boolean schemaSupported() {
    return opaque == null;
  }

  public Optional<PatternProviderBlockEntity> resolveProvider() {
    if (!(level instanceof ServerLevel server)) {
      return Optional.empty();
    }
    ServerThreadGuard.require(server, "Pattern mirror resolution");
    if (opaque != null
        || target == null
        || isRemoved()
        || !server.hasChunkAt(worldPosition)
        || server.getBlockEntity(worldPosition) != this
        || !target.dimension().equals(server.dimension().location())
        || !server.hasChunkAt(target.position())) {
      return Optional.empty();
    }
    var entity = server.getBlockEntity(target.position());
    return entity instanceof PatternProviderBlockEntity provider
        && !provider.isRemoved()
        && provider.schemaSupported()
        && provider.providerId().equals(target.provider()) ? Optional.of(provider) : Optional.empty();
  }

  public boolean bind(Player player, long expectedRevision, PatternProviderReference reference) {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Pattern mirror binding");
    if (opaque != null
        || isRemoved()
        || expectedRevision != bindingRevision
        || bindingRevision == Long.MAX_VALUE
        || !reference.dimension().equals(server.dimension().location())
        || player.level() != server
        || !player.isAlive()
        || player.isSpectator()
        || !player.mayBuild()
        || !player.canInteractWithBlock(worldPosition, 0)
        || !server.mayInteract(player, worldPosition)
        || !server.hasChunkAt(worldPosition)
        || server.getBlockEntity(worldPosition) != this
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching()) {
      return false;
    }
    // A missing chunk pauses an existing binding; a new binding requires a live
    // identity proof.
    if (!server.hasChunkAt(reference.position())
        || !(server.getBlockEntity(reference.position()) instanceof PatternProviderBlockEntity provider)
        || !provider.canMutate()
        || !provider.providerId().equals(reference.provider())) {
      return false;
    }
    if (reference.equals(target)) {
      return true;
    }
    var previous = resolveProvider().orElse(null);
    target = reference;
    bindingRevision++;
    setChanged();
    notifyStructure();
    notifyProviders(previous);
    return true;
  }

  @Override
  public int machineColor() {
    return machineColor;
  }
  @Override
  public boolean machineColorAssigned() {
    return machineColorAssigned;
  }
  @Override
  public void setMachineColor(int color) {
    color &= 0xFFFFFF;
    boolean changed = !machineColorAssigned || machineColor != color;
    machineColorAssigned = true;
    machineColor = color;
    if (changed) {
      MachineColorable.update(this);
    }
  }
  @Override
  public void onLoad() {
    super.onLoad();
    notifyStructure();
  }
  @Override
  public void setRemoved() {
    notifyStructure();
    super.setRemoved();
  }
  @Override
  public void onChunkUnloaded() {
    notifyStructure();
    super.onChunkUnloaded();
  }
  private void notifyStructure() {
    if (level instanceof ServerLevel server) {
      StructureService.blockChanged(server, worldPosition);
    }
  }

  private void notifyProviders(PatternProviderBlockEntity previous) {
    // Resolve the old target before rebinding: structure invalidation can only
    // discover the new target once the binding has changed.
    if (previous != null) {
      previous.connection().patternsChanged();
    }
    var current = resolveProvider().orElse(null);
    if (current != null && current != previous) {
      current.connection().patternsChanged();
    }
  }
  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    MachineColorable.save(this, tag, machineColor);
    if (opaque != null) {
      tag.put(DATA_TAG, opaque.copy());
      return;
    }
    var data = new CompoundTag();
    data.putInt("schema", 1);
    data.putLong("revision", bindingRevision);
    if (target != null) {
      data.put("target", target.save());
    }
    tag.put(DATA_TAG, data);
  }
  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Pattern mirror load");
      if (UpgradeLifecyclePublisher.isDispatchingAny()
          || RecipeLifecyclePublisher.isDispatching()
          || SmartInterfaceEventPublisher.isDispatching()) {
        throw new IllegalStateException("Cannot reload mirror during a read-only callback");
      }
    }
    super.loadAdditional(tag, registries);
    long previousRevision = bindingRevision;
    boolean live = level instanceof ServerLevel
        && level.hasChunkAt(worldPosition)
        && level.getBlockEntity(worldPosition) == this;
    var previous = live ? resolveProvider().orElse(null) : null;
    machineColorAssigned = tag.contains(COLOR_TAG);
    machineColor = MachineColorable.load(tag);
    if (level != null && level.isClientSide) {
      return;
    }
    target = null;
    bindingRevision = 0;
    opaque = null;
    if (tag.contains(DATA_TAG)) {
      try {
        if (!tag.contains(DATA_TAG, Tag.TAG_COMPOUND)) {
          throw new IllegalArgumentException();
        }
        var data = tag.getCompound(DATA_TAG);
        if (!data.contains("schema", Tag.TAG_INT)
            || data.getInt("schema") != 1
            || !data.contains("revision", Tag.TAG_LONG)
            || data.getLong("revision") < 0
            || data.contains("target") && !data.contains("target", Tag.TAG_COMPOUND)) {
          throw new IllegalArgumentException();
        }
        bindingRevision = data.getLong("revision");
        if (data.contains("target")) {
          target = PatternProviderReference.load(data.getCompound("target"));
        }
      } catch (RuntimeException invalid) {
        opaque = tag.get(DATA_TAG).copy();
        target = null;
      }
    }
    if (live) {
      bindingRevision = Math
          .max(bindingRevision, previousRevision == Long.MAX_VALUE ? Long.MAX_VALUE : previousRevision + 1);
    }
    notifyStructure();
    if (live) {
      notifyProviders(previous);
    }
  }
  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor);
    return tag;
  }
}
