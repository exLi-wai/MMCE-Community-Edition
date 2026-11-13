package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.machine.network.pattern.PatternJobs;
import com.lw.modularmachine.machine.network.pattern.PatternDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import com.lw.modularmachine.machine.network.pattern.PatternRecipeDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternRecipeDefinition.ExecutionCosts;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.machine.network.pattern.PatternResourceLedger.JobStatus;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal recipe ownership and per-container source expansion. Cached
 * declarations contain no world authority; every use revalidates the live owner
 * and gateway.
 */
@ApiStatus.Internal
public final class PatternProductionService {
  private static final Map<StructureService.StructureSubject, OwnerState> OWNERS = new WeakHashMap<>();
  private static final Map<RecipeCatalog, Map<Compatibility, Boolean>> COMPATIBILITY = new WeakHashMap<>();
  private static final Map<MachineRecipe, Map<ExecutionCosts, Declaration>> DECLARATIONS = new WeakHashMap<>();
  private static final Map<PatternProviderBlockEntity, ProviderCache> PROVIDERS = new WeakHashMap<>();

  private record ProviderCache(
      UUID instance,
      long revision,
      List<PatternProviderState.ContainerView> containers,
      Map<PatternDefinition, PatternRecipeDefinition> decoded) {
  }

  private static final class OwnerState {
    private WeakReference<StructureService.Registration> registration = new WeakReference<>(null);
    private long structureRevision = -1;
    private List<Gateway> gateways = List.of();
    private final Map<String, WeakReference<RecipeCatalog>> catalogs = new HashMap<>();
    private long rotation;
  }

  private record Gateway(BlockPos position, String selector) {
  }

  private record ProviderSelector(PatternProviderBlockEntity provider, String selector) {
  }

  private record LiveGateway(PatternProviderBlockEntity provider, BlockEntity entity, String selector) {
  }

  private record Compatibility(
      PatternRecipeDefinition pattern,
      String selector,
      String lockedRecipe,
      FactoryUpgradeSnapshot upgrades) {
  }

  private record Declaration(Map<String, Map<PatternResourceKey, Long>> inputs, Map<PatternResourceKey, Long> outputs) {
    private static final Declaration INVALID = new Declaration(Map.of(), Map.of());
  }

  /** Called with the actual ordinary or factory-thread context catalog. */
  public static void observe(StructureService.StructureSubject owner, RecipeCatalog catalog) {
    observe(owner, catalog, null);
  }

  public static void observe(StructureService.StructureSubject owner, RecipeCatalog catalog, String factoryThread) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Pattern recipe catalog observation");
    if (!current(owner, level) || !catalog.isCurrent() || !catalog.machineId().equals(owner.structureMachineId())) {
      return;
    }
    var state = OWNERS.computeIfAbsent(owner, ignored -> new OwnerState());
    boolean changed = refreshGateways(owner, level, state);
    state.catalogs.values().removeIf(reference -> reference.get() == null || !reference.get().isCurrent());
    String context = factoryThread == null ? "" : factoryThread;
    var previous = state.catalogs.get(context);
    if (previous == null || previous.get() != catalog) {
      state.catalogs.put(context, new WeakReference<>(catalog));
      changed = true;
    }
    if (changed) {
      for (var gateway : liveGateways(owner, level, state)) {
        gateway.provider().connection().patternsChanged();
      }
    }
  }

  public static List<PatternInputSource> sources(StructureService.StructureSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return List.of();
    }
    ServerThreadGuard.require(level, "Pattern source capture");
    if (!current(owner, level)) {
      return List.of();
    }
    var state = OWNERS.computeIfAbsent(owner, ignored -> new OwnerState());
    refreshGateways(owner, level, state);
    var result = new ArrayList<PatternInputSource>();
    var independent = new ArrayList<PatternInputSource>();
    var jobEligibility = new HashMap<UUID, Boolean>();
    for (var gateway : liveGateways(owner, level, state)) {
      var provider = gateway.provider();
      var providerCache = capture(provider);
      for (var container : providerCache.containers()) {
        if (container.retired() || container.resources().returnRequested()) {
          continue;
        }
        var decoded = providerCache.decoded().get(container.pattern());
        if (decoded == null) {
          continue;
        }
        if (!container.resources().unowned().isEmpty()) {
          var source = new PatternInputSource(
              provider,
              gateway.entity(),
              gateway.selector(),
              container.address(),
              null,
              null,
              container,
              decoded);
          if (source.startLimit() > 0) {
            result.add(source);
          }
        }
        for (var receipt : container.resources().receipts().entrySet()) {
          if (receipt.getValue().status() == JobStatus.ACTIVE
              && !receipt.getValue().remaining().isEmpty()
              && jobEligibility.computeIfAbsent(receipt.getKey(), id -> PatternJobs.canStart(level, id))) {
            var source = new PatternInputSource(
                provider,
                gateway.entity(),
                gateway.selector(),
                container.address(),
                receipt.getKey(),
                null,
                container,
                decoded);
            if (source.startLimit() > 0) {
              result.add(source);
            }
          }
        }
      }
      independent.add(new PatternInputSource(provider, gateway.entity(), gateway.selector(), null, null, null));
    }
    long turn = state.rotation++;
    if (!result.isEmpty()) {
      Collections.rotate(result, -(int) Math.floorMod(turn, result.size()));
    }
    if (!independent.isEmpty()) {
      Collections.rotate(independent, -(int) Math.floorMod(turn, independent.size()));
    }
    result.addAll(independent);

    return List.copyOf(result);
  }

  private static ProviderCache capture(PatternProviderBlockEntity provider) {
    var previous = PROVIDERS.get(provider);
    if (previous != null
        && previous.instance().equals(provider.instanceId())
        && previous.revision() == provider.state().revision()) {
      return previous;
    }
    var containers = provider.state().containers();
    var decoded = new HashMap<PatternDefinition, PatternRecipeDefinition>();
    for (var container : containers) {
      if (container.retired() || decoded.containsKey(container.pattern())) {
        continue;
      }
      var pattern = previous == null ? null : previous.decoded().get(container.pattern());
      if (pattern == null) {
        pattern = provider.connection().pattern(container.pattern().stack(provider.getLevel().registryAccess()))
            .orElse(null);
      }
      if (pattern != null) {
        decoded.put(container.pattern(), pattern);
      }
    }
    var result = new ProviderCache(provider.instanceId(), provider.state().revision(), containers, Map.copyOf(decoded));
    PROVIDERS.put(provider, result);

    return result;
  }

  private static boolean current(StructureService.StructureSubject owner, ServerLevel level) {
    var position = owner.structurePosition();
    var registration = owner.structureRegistration();

    return !owner.structureRemoved()
        && owner.structureSchemaSupported()
        && level.hasChunkAt(position)
        && level.getBlockEntity(position) == owner
        && registration != null
        && registration.controller == owner
        && registration.canCommit(level);
  }

  private static boolean refreshGateways(StructureService.StructureSubject owner, ServerLevel level, OwnerState state) {
    var registration = owner.structureRegistration();
    long revision = registration.verifier.revision();
    if (state.registration.get() == registration && state.structureRevision == revision) {
      return false;
    }
    var gateways = new ArrayList<Gateway>();
    for (int index = 0; index < registration.pattern.definition.parts().size(); index++) {
      var position = registration.pattern.position(index);
      if (position.equals(owner.structurePosition()) || !level.hasChunkAt(position)) {
        continue;
      }
      var entity = level.getBlockEntity(position);
      if (entity instanceof PatternProviderBlockEntity || entity instanceof PatternMirrorBlockEntity) {
        gateways.add(new Gateway(position, registration.pattern.definition.parts().get(index).selector()));
      }
    }
    state.registration = new WeakReference<>(registration);
    state.structureRevision = revision;
    state.gateways = List.copyOf(gateways);

    return true;
  }

  private static List<LiveGateway> liveGateways(StructureService.StructureSubject owner, ServerLevel level, OwnerState state) {
    if (!current(owner, level)) {
      return List.of();
    }
    refreshGateways(owner, level, state);
    var unique = new LinkedHashMap<ProviderSelector, LiveGateway>();
    for (var gateway : state.gateways) {
      if (!level.hasChunkAt(gateway.position())) {
        continue;
      }
      var entity = level.getBlockEntity(gateway.position());
      var provider = entity instanceof PatternProviderBlockEntity direct ? direct : entity instanceof PatternMirrorBlockEntity mirror ? mirror
          .resolveProvider().orElse(null) : null;
      if (provider != null && provider.getLevel() == level && provider.canMutate()) {
        unique.putIfAbsent(
            new ProviderSelector(provider, gateway.selector()),
            new LiveGateway(provider, entity, gateway.selector()));
      }
    }

    return List.copyOf(unique.values());
  }

  /** No matching current native recipe means no AE crafting promise. */
  public static boolean isCompatible(PatternProviderBlockEntity provider, PatternRecipeDefinition pattern) {
    if (!(provider.getLevel() instanceof ServerLevel level) || !provider.canMutate()) {
      return false;
    }
    ServerThreadGuard.require(level, "Pattern export compatibility");
    for (var entry : OWNERS.entrySet()) {
      var owner = entry.getKey();
      if (owner.structureLevel() != level || !current(owner, level)) {
        continue;
      }
      var state = entry.getValue();
      var gateways = liveGateways(owner, level, state);
      for (var gateway : gateways) {
        if (gateway.provider() != provider) {
          continue;
        }
        for (var observed : state.catalogs.entrySet()) {
          var catalog = observed.getValue().get();
          var upgrades = currentUpgrades(owner, observed.getKey());
          if (catalog != null
              && upgrades != null
              && catalog.isCurrent()
              && catalog.machineId().equals(owner.structureMachineId())
              && contextCurrent(owner, catalog, upgrades, level)) {
            String locked = "";
            if (owner instanceof FactoryControllerBlockEntity factory) {
              var thread = factory.scheduler().channel(observed.getKey());
              if (thread.lockedRecipe() != null) {
                locked = thread.lockedRecipe().toString();
              }
            }
            var key = new Compatibility(pattern, gateway.selector(), locked, upgrades);
            var cache = COMPATIBILITY.computeIfAbsent(catalog, ignored -> new HashMap<>());
            if (cache.size() >= 1024 && !cache.containsKey(key)) {
              cache.clear();
            }
            boolean matches = cache
                .computeIfAbsent(key, ignored -> catalog.recipes().candidates().stream().anyMatch(candidate -> {
                  if (!key.lockedRecipe().isEmpty() && !candidate.id().equals(key.lockedRecipe())) {
                    return false;
                  }
                  var recipe = catalog.definition(candidate.id());
                  return recipe != null && matches(pattern, gateway.selector(), recipe, upgrades, level);
                }));
            if (matches) {
              return true;
            }
          }
        }
      }
    }

    return false;
  }

  private static FactoryUpgradeSnapshot currentUpgrades(StructureService.StructureSubject owner, String threadId) {
    if (owner instanceof MachineControllerBlockEntity ordinary) {
      var upgrades = ordinary.upgradeSnapshot();
      return ordinary.upgradeSnapshotCurrent(upgrades) ? upgrades : null;
    }
    if (owner instanceof FactoryControllerBlockEntity factory && threadId != null) {
      var thread = factory.scheduler().channel(threadId);
      if (thread == null
          || !thread.enabled()
          || factory.scheduler().channels().indexOf(thread) >= factory.maximumBusinessThreads()
          || !factory.upgradeRestrictionsCurrent()) {
        return null;
      }
      return thread.upgradeSnapshot();
    }

    return null;
  }

  private static boolean contextCurrent(StructureService.StructureSubject owner, RecipeCatalog catalog, FactoryUpgradeSnapshot upgrades, ServerLevel level) {
    var expected = new ArrayList<>(owner.structureRegistration().activeRecipeModifiers(level));
    expected.addAll(upgrades.recipeModifiers());
    expected.addAll(MachineControllerScriptDataService.modifiers(owner));

    return catalog.contextRules().equals(expected);
  }

  /**
   * Revoke remembered export promises when an owner unloads or changes structure.
   */
  public static void invalidate(StructureService.StructureSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Pattern owner invalidation");
    var state = OWNERS.remove(owner);
    if (state == null) {
      return;
    }
    for (var gateway : state.gateways) {
      if (!level.hasChunkAt(gateway.position())) {
        continue;
      }
      var entity = level.getBlockEntity(gateway.position());
      var provider = entity instanceof PatternProviderBlockEntity direct ? direct : entity instanceof PatternMirrorBlockEntity mirror ? mirror
          .resolveProvider().orElse(null) : null;
      if (provider != null) {
        provider.connection().patternsChanged();
      }
    }
  }

  public static boolean matches(RecipeExecutionTicket ticket, MachineRecipe recipe, String factoryThread) {
    ticket.requireServerThread();
    var source = ticket.patternSource();
    if (source == null || source.container() == null) {
      return true;
    }
    if (source.definition() == null || recipe == null) {
      return false;
    }
    var upgrades = ticket.owner() instanceof MachineControllerBlockEntity ? ticket
        .ordinaryUpgrades() : currentUpgrades(ticket.owner(), factoryThread);

    return upgrades != null && matches(source.definition(), source.selector(), recipe, upgrades, ticket.level());
  }

  private static boolean matches(PatternRecipeDefinition pattern, String selector, MachineRecipe recipe, FactoryUpgradeSnapshot upgrades, ServerLevel level) {
    ExecutionCosts costs;
    try {
      costs = PatternRecipeDefinition.executionCosts(recipe.duration(), recipe.fluidPerTick().getAmount(), upgrades);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return false;
    }
    var cache = DECLARATIONS.computeIfAbsent(recipe, ignored -> new HashMap<>());
    if (cache.size() >= 64 && !cache.containsKey(costs)) {
      cache.clear();
    }
    var declaration = cache.computeIfAbsent(costs, ignored -> declaration(recipe, costs, level));
    if (declaration == Declaration.INVALID) {
      return false;
    }
    var input = declaration.inputs().getOrDefault(selector, Map.of());

    return pattern.matchesNativeDeclaration(input, declaration.outputs());
  }

  private static Declaration declaration(MachineRecipe recipe, ExecutionCosts costs, ServerLevel level) {
    var inputs = new HashMap<String, Map<PatternResourceKey, Long>>();
    var outputs = new HashMap<PatternResourceKey, Long>();
    try {
      for (var item : recipe.inputs()) {
        if (item.chance() != 1 || item.ignoresComponents()) {
          return Declaration.INVALID;
        }
        add(
            inputs.computeIfAbsent(item.selector(), ignored -> new HashMap<>()),
            new NetworkPortResource.Item(item.prototype(level.registryAccess())),
            item.amount(),
            level);
      }
      for (var item : recipe.outputs()) {
        if (item.chance() != 1 || item.ignoresComponents()) {
          return Declaration.INVALID;
        }
        add(outputs, new NetworkPortResource.Item(item.prototype(level.registryAccess())), item.amount(), level);
      }
      for (var fluid : recipe.fluidInputs()) {
        add(
            inputs.computeIfAbsent(recipe.selectors().fluidInput(), ignored -> new HashMap<>()),
            new NetworkPortResource.Fluid(fluid),
            fluid.getAmount(),
            level);
      }
      for (var fluid : recipe.fluidOutputs()) {
        add(outputs, new NetworkPortResource.Fluid(fluid), fluid.getAmount(), level);
      }
      var perTick = recipe.fluidPerTick();
      if (!perTick.isEmpty()) {
        add(
            inputs.computeIfAbsent(recipe.selectors().fluidPerTick(), ignored -> new HashMap<>()),
            new NetworkPortResource.Fluid(perTick),
            costs.totalPerTick(costs.fluidPerTick()),
            level);
      }
      for (var chemical : recipe.chemicals().inputs()) {
        add(
            inputs.computeIfAbsent(chemical.selector(), ignored -> new HashMap<>()),
            new NetworkPortResource.Chemical(chemical.chemical()),
            chemical.amount(),
            level);
      }
      for (var chemical : recipe.chemicals().outputs()) {
        add(outputs, new NetworkPortResource.Chemical(chemical.chemical()), chemical.amount(), level);
      }
      for (var chemical : recipe.chemicals().perTick()) {
        add(
            inputs.computeIfAbsent(chemical.selector(), ignored -> new HashMap<>()),
            new NetworkPortResource.Chemical(chemical.chemical()),
            costs.totalPerTick(chemical.amount()),
            level);
      }
      inputs.replaceAll((selector, resources) -> Map.copyOf(resources));
      return new Declaration(Map.copyOf(inputs), Map.copyOf(outputs));
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return Declaration.INVALID;
    }
  }

  private static void add(Map<PatternResourceKey, Long> target, NetworkPortResource resource, long amount, ServerLevel level) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Nonpositive native pattern quantity");
    }
    target.merge(PatternResourceKey.capture(resource, level.registryAccess()), amount, Math::addExact);
  }

  /**
   * Content changes wake only controllers that currently reference this physical
   * host.
   */
  public static void resourcesChanged(PatternProviderBlockEntity provider) {
    if (!(provider.getLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Pattern resource wakeup");
    for (var entry : OWNERS.entrySet()) {
      var owner = entry.getKey();
      if (owner.structureLevel() == level
          && current(owner, level)
          && liveGateways(owner, level, entry.getValue()).stream()
              .anyMatch(gateway -> gateway.provider() == provider)) {
        if (owner instanceof MachineControllerBlockEntity ordinary) {
          ordinary.patternResourcesChanged();
        } else if (owner instanceof FactoryControllerBlockEntity factory) {
          factory.patternResourcesChanged();
        }
      }
    }
  }

  private PatternProductionService() {
  }
}
