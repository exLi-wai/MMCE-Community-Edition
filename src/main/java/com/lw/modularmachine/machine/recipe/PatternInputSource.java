package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.machine.network.pattern.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Internal frozen resource scope. Gateways never own a second inventory. */
@ApiStatus.Internal
public final class PatternInputSource {
  private final PatternProviderBlockEntity provider;
  private final BlockEntity gateway;
  private final long bindingRevision;
  private final long resourceRevision;
  private final long stateRevision;
  private final UUID instance;
  private final UUID providerId;
  private final String selector;
  private final PatternContainerRef container;
  private final UUID job;
  private final UUID run;
  private final PatternRecipeDefinition definition;
  private final Map<PatternResourceKey, Long> resources;
  private final int startLimit;
  private final PatternProviderState.ContainerView capturedContainer;
  private final List<ItemStack> independentItems;
  private final FluidStack independentFluid;

  PatternInputSource(PatternProviderBlockEntity provider, BlockEntity gateway, String selector, PatternContainerRef container, UUID job, UUID run) {
    this(provider, gateway, selector, container, job, run,
        container == null ? null : provider.state().container(container).orElseThrow(), null);
  }

  PatternInputSource(PatternProviderBlockEntity provider, BlockEntity gateway, String selector, PatternContainerRef container, UUID job, UUID run, PatternProviderState.ContainerView view, PatternRecipeDefinition decoded) {
    this.provider = provider;
    this.gateway = gateway;
    this.selector = selector;
    this.container = container;
    this.job = job;
    this.run = run;
    bindingRevision = gateway instanceof PatternMirrorBlockEntity mirror ? mirror.bindingRevision() : 0;
    resourceRevision = provider.resourceRevision();
    stateRevision = provider.state().revision();
    instance = provider.instanceId();
    providerId = provider.providerId();
    capturedContainer = view;
    var items = new ArrayList<ItemStack>();
    for (int slot = 0; slot < provider.independentItems().getSlots(); slot++) {
      items.add(
          ItemStackSnapshots
              .detached(provider.independentItems().getStackInSlot(slot), provider.getLevel().registryAccess()));
    }
    independentItems = List.copyOf(items);
    independentFluid = provider.independentFluid().getFluid().copy();
    if (container == null) {
      definition = null;
      resources = Map.of();
      startLimit = Integer.MAX_VALUE;
    } else {
      if (view == null || !view.address().equals(container)) {
        throw new IllegalArgumentException("Mismatched pattern source snapshot");
      }
      definition = decoded != null ? decoded : provider.connection()
          .pattern(view.pattern().stack(provider.getLevel().registryAccess())).orElse(null);
      var ledger = view.resources();
      var receipt = job == null ? null : ledger.receipts().get(job);
      if (run != null) {
        if (job == null ? !ledger.unownedRuns().contains(run) : receipt == null || !receipt.runs().contains(run)) {
          throw new IllegalStateException("Pattern paid run has no matching receipt");
        }
        resources = job == null ? ledger.unowned() : receipt.remaining();
        startLimit = 0;
      } else {
        resources = ledger.returnRequested()
            || job != null && (receipt == null || receipt.status() != PatternResourceLedger.JobStatus.ACTIVE) ? Map
                .of() : job == null ? ledger.unowned() : receipt.remaining();
        boolean running = job == null ? !ledger.unownedRuns().isEmpty() : receipt != null && !receipt.runs().isEmpty();
        long limit = running || definition == null ? 0 : Integer.MAX_VALUE;
        if (limit > 0) {
          for (var input : definition.inputs().entrySet()) {
            limit = Math.min(limit, resources.getOrDefault(input.getKey(), 0L) / input.getValue());
          }
        }
        startLimit = (int) limit;
      }
    }
  }

  public PatternProviderBlockEntity provider() {
    return provider;
  }
  public String selector() {
    return selector;
  }
  public PatternContainerRef container() {
    return container;
  }
  public UUID job() {
    return job;
  }
  public UUID run() {
    return run;
  }
  public UUID instance() {
    return instance;
  }
  public UUID providerId() {
    return providerId;
  }
  public long stateRevision() {
    return stateRevision;
  }
  public long revision() {
    return resourceRevision;
  }
  public PatternRecipeDefinition definition() {
    return definition;
  }
  public Map<PatternResourceKey, Long> resources() {
    return resources;
  }
  public int startLimit() {
    return startLimit;
  }
  public boolean sameScope(PatternInputSource other) {
    return other != null
        && provider == other.provider
        && Objects.equals(container, other.container)
        && Objects.equals(job, other.job)
        && selector.equals(other.selector);
  }
  public boolean current() {
    if (!(provider.getLevel() instanceof ServerLevel level)
        || provider.isRemoved()
        || !level.hasChunkAt(provider.getBlockPos())
        || level.getBlockEntity(provider.getBlockPos()) != provider
        || !provider.schemaSupported()
        || !provider.canMutate()
        || !provider.instanceId().equals(instance)) {
      return false;
    }
    long currentResources = provider.resourceRevision();
    long currentState = provider.state().revision();
    if (currentResources != resourceRevision || currentState != stateRevision) {
      if (capturedContainer != null && !provider.state().matchesSource(capturedContainer, job)
          || provider.independentItems().getSlots() != independentItems.size()) {
        return false;
      }
      for (int slot = 0; slot < independentItems.size(); slot++) {
        if (!ItemStack.matches(independentItems.get(slot), provider.independentItems().getStackInSlot(slot))) {
          return false;
        }
      }
      var fluid = provider.independentFluid().getFluid();
      if (independentFluid.getAmount() != fluid.getAmount()
          || !FluidStack.isSameFluidSameComponents(independentFluid, fluid)) {
        return false;
      }
      // Component comparisons may reenter; the checked contents must still be
      // the current generation before handing authority to a transaction.
      if (!provider.canMutate()
          || !provider.instanceId().equals(instance)
          || provider.resourceRevision() != currentResources
          || provider.state().revision() != currentState) {
        return false;
      }
    }
    if (run != null) {
      return true;
    }
    if (gateway == null
        || gateway.isRemoved()
        || gateway.getLevel() != level
        || !level.hasChunkAt(gateway.getBlockPos())
        || level.getBlockEntity(gateway.getBlockPos()) != gateway) {
      return false;
    }
    if (gateway instanceof PatternMirrorBlockEntity mirror
        && (mirror.bindingRevision() != bindingRevision || mirror.resolveProvider().orElse(null) != provider)) {
      return false;
    }
    return PatternJobs.canStart(level, job);
  }
}
