package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.google.gson.JsonElement;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterImports;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterScriptSources;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.recipe.resource.chemical.ChemicalResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.fluid.FluidTickResourceExecution;

/**
 * Validated candidates are staged by prospective RecipeManager; only current
 * resources gain live authority.
 */
public final class MachineRecipeService {
  private static final Map<RecipeManager, Prepared> PREPARED = new IdentityHashMap<>();
  private static final Map<MinecraftServer, Published> ACTIVE = new IdentityHashMap<>();
  private static volatile MachineRecipeReloadListener.SourceCache nativeSources = new MachineRecipeReloadListener.SourceCache();

  private record Prepared(
      long structureEpoch,
      Collection<RecipeHolder<?>> recipeView,
      Map<ResourceLocation, RecipeCatalog> catalogs,
      Map<ResourceLocation, MachineRecipe> definitions,
      Map<ResourceLocation, RecipeModifierTemplate> templates,
      MachineRecipeJson.Defaults defaults,
      long recipeAdapterScriptRevision,
      RecipeScriptCallbacks.Snapshot itemCallbacks) {
  }

  private MachineRecipeService() {
  }

  static void stage(RecipeManager manager, Map<ResourceLocation, MachineRecipe> expected, Map<ResourceLocation, JsonElement> sources, HolderLookup.Provider registries) {
    stage(manager, expected, sources, registries, RecipeAdapterTags.from(registries));
  }

  static void stage(RecipeManager manager, Map<ResourceLocation, MachineRecipe> expected, Map<ResourceLocation, JsonElement> sources, HolderLookup.Provider registries, RecipeAdapterTags tags) {
    var defaults = MachineRecipeJson.currentDefaults();
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null) {
      ServerThreadGuard.require(server, "Machine recipe catalog accessed");
    }
    // Derive only from the last live publication. None of the previous
    // authority, callback snapshots or contextual catalogs are carried over.
    var previous = server == null ? null : ACTIVE.get(server);
    var previousCatalogs = previous == null ? Map.<ResourceLocation, RecipeCatalog>of() : previous.prepared.catalogs();
    var actual = new LinkedHashMap<ResourceLocation, MachineRecipe>();
    for (var holder : manager.getAllRecipesFor(MachineRecipeRegistries.ITEM_TYPE.get())) {
      if (!sources.containsKey(holder.id())) {
        throw new IllegalArgumentException(
            holder.id() + ": Machine recipes require a validated native resource or script declaration");
      }
      actual.put(holder.id(), holder.value());
    }
    if (!actual.keySet().equals(expected.keySet())) {
      var missing = new LinkedHashSet<>(expected.keySet());
      missing.removeAll(actual.keySet());
      var unexpected = new LinkedHashSet<>(actual.keySet());
      unexpected.removeAll(expected.keySet());
      throw new IllegalArgumentException(
          "Native machine recipe set is incomplete or contains an unvalidated recipe: missing " + missing + ", unvalidated " + unexpected);
    }
    var machines = StructureService.definitionsSnapshot();
    var imports = RecipeAdapterImports.generate(manager, machines, registries, tags, sources);
    var declarations = new LinkedHashMap<>(expected);
    var originalSources = new LinkedHashMap<>(sources);
    for (var entry : imports.recipes().entrySet()) {
      if (declarations.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
        throw new IllegalArgumentException(entry.getKey() + ": Imported recipe collides with a native declaration");
      }
      actual.put(entry.getKey(), entry.getValue());
      originalSources.put(entry.getKey(), imports.sources().get(entry.getKey()));
    }
    var candidates = new HashMap<ResourceLocation, List<RecipeCandidate<ItemResourceKey>>>();
    var effective = new LinkedHashMap<ResourceLocation, MachineRecipe>();
    var templates = new LinkedHashMap<ResourceLocation, RecipeModifierTemplate>();
    for (var entry : declarations.entrySet()) {
      var original = actual.get(entry.getKey());
      if (!entry.getValue().sameDefinition(original)) {
        throw new IllegalArgumentException(entry.getKey() + ": Native recipe differs from validated resource");
      }
      var machine = StructureService.definition(original.machineId());
      if (machine == null) {
        throw new IllegalArgumentException(entry.getKey() + ": Unknown machine " + original.machineId());
      }
      var recipe = original;
      var source = originalSources.get(entry.getKey());
      if (source == null || !source.isJsonObject()) {
        throw new IllegalArgumentException(entry.getKey() + ": Missing original recipe declaration");
      }
      var template = new RecipeModifierTemplate(
          source.getAsJsonObject(),
          machine.recipeModifiers(),
          machine.failureAction());
      templates.put(entry.getKey(), template);
      if (!machine.recipeModifiers().isEmpty()
          || !source.getAsJsonObject().has("parallelized")
          || !source.getAsJsonObject().has("failure_action")) {
        try {
          var resolved = template.resolve(List.of(), registries, false, defaults);
          if (!resolved.sameDefinition(original)) {
            recipe = resolved;
          }
        } catch (RuntimeException invalid) {
          throw new IllegalArgumentException(entry.getKey() + ": Invalid machine recipe modifiers", invalid);
        }
      }
      validatePorts(entry.getKey(), recipe, machine);
      RecipeResourceExecutionRegistry.validateDefinition(entry.getKey(), recipe.resourceData(), registries);
      RecipeTriggeredResources.validateRegistry(recipe.triggeredResources());
      var compiled = reuseCandidate(previousCatalogs.get(recipe.machineId()), entry.getKey(), recipe);
      if (compiled == null) {
        compiled = recipe.compile(entry.getKey(), registries);
      }
      candidates.computeIfAbsent(recipe.machineId(), ignored -> new ArrayList<>()).add(compiled);
      effective.put(entry.getKey(), recipe);
    }
    // A validated machine with no recipes still has a published catalog.
    machines.keySet().forEach(machine -> candidates.putIfAbsent(machine, List.of()));
    var catalogs = prepareCatalogs(candidates, effective, previousCatalogs);
    boolean changed = !imports.recipes().isEmpty()
        || effective.entrySet().stream().anyMatch(entry -> entry.getValue() != actual.get(entry.getKey()));
    if (!RecipeAdapterScriptSources.isCurrent(imports.scriptRevision())) {
      throw new IllegalStateException("Recipe adapter scripts changed before recipe publication");
    }
    if (changed) {
      var replacements = new ArrayList<RecipeHolder<?>>();
      var installed = new LinkedHashSet<ResourceLocation>();
      for (var holder : manager.getRecipes()) {
        var replacement = effective.get(holder.id());
        replacements.add(replacement == null ? holder : new RecipeHolder<>(holder.id(), replacement));
        installed.add(holder.id());
      }
      for (var entry : effective.entrySet()) {
        if (installed.add(entry.getKey())) {
          replacements.add(new RecipeHolder<>(entry.getKey(), entry.getValue()));
        }
      }
      // This is the prospective reload manager. Preserve all other recipe types and
      // publish once after validation.
      manager.replaceRecipes(replacements);
    }
    var candidate = new Prepared(
        StructureService.definitionEpoch(),
        manager.getRecipes(),
        catalogs,
        Map.copyOf(effective),
        Map.copyOf(templates),
        defaults,
        imports.scriptRevision(),
        RecipeScriptCallbacks.snapshot());
    PREPARED.entrySet().removeIf(
        entry -> entry.getKey() != manager
            && ACTIVE.values().stream().noneMatch(active -> active.manager == entry.getKey()));
    PREPARED.put(manager, candidate);
  }

  public static Optional<RecipeCatalog> catalogFor(MachineControllerBlockEntity controller) {
    return catalogFor((StructureService.StructureSubject) controller);
  }

  private static RecipeCandidate<ItemResourceKey> reuseCandidate(RecipeCatalog previous, ResourceLocation id, MachineRecipe recipe) {
    if (previous == null || !recipe.hasReusableIndexData()) {
      return null;
    }
    var key = id.toString();
    var definition = previous.definition(key);
    return definition != null && recipe.sameDefinition(definition) && definition.hasReusableIndexData() ? previous
        .recipes().candidate(key).orElseThrow() : null;
  }

  /**
   * Rebuilds only changed machine indexes before the prospective generation can
   * be installed. Candidate identity proves that the same validated data and
   * recipe ID were reused without changing the machine's ranking inputs.
   */
  private static Map<ResourceLocation, RecipeCatalog> prepareCatalogs(Map<ResourceLocation, List<RecipeCandidate<ItemResourceKey>>> candidates, Map<ResourceLocation, MachineRecipe> effective, Map<ResourceLocation, RecipeCatalog> previousCatalogs) {
    var grouped = new HashMap<ResourceLocation, Map<String, MachineRecipe>>();
    effective.forEach(
        (id, recipe) -> grouped.computeIfAbsent(recipe.machineId(), ignored -> new HashMap<>())
            .put(id.toString(), recipe));
    var result = new HashMap<ResourceLocation, RecipeCatalog>();
    candidates.forEach((machine, values) -> {
      var previous = previousCatalogs.get(machine);
      if (previous != null
          && previous.recipes().size() == values.size()
          && values.stream().allMatch(value -> previous.recipes().candidate(value.id()).orElse(null) == value)) {
        result.put(machine, previous);
        return;
      }
      var definitions = grouped.getOrDefault(machine, Map.of());
      result.put(machine, RecipeCatalog.fromDefinitions(machine, values, definitions, () -> false, null, List.of()));
    });
    return Map.copyOf(result);
  }

  public static Optional<RecipeCatalog> catalogFor(StructureService.StructureSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    var base = published == null ? null : published.catalogs.get(owner.structureMachineId());
    if (base == null || base.recipes().size() == 0 || owner instanceof FactoryControllerBlockEntity) {
      return Optional.ofNullable(base);
    }
    if (owner instanceof MachineControllerBlockEntity ordinary) {
      ordinary.refreshUpgradeSources();
      var upgrades = ordinary.upgradeSnapshot();
      if (!ordinary.upgradeSnapshotCurrent(upgrades)) {
        return Optional.empty();
      }
      return contextCatalog(published, owner, base, upgrades.recipeModifiers(), true);
    }
    if (!published.durationMultiplier
        && published.defaults.equals(published.prepared.defaults())
        && MachineControllerScriptDataService.revision(owner) == 0
        && StructureService.definition(owner.structureMachineId()).structureModifiers().isEmpty()) {
      return Optional.of(base);
    }

    return contextCatalog(published, owner, base, List.of(), false);
  }

  static void validatePorts(ResourceLocation id, MachineRecipe recipe, MachineDefinition machine) {
    for (var requirement : recipe.interfaceInputs()) {
      if (!machine.interfaceTypes().containsKey(requirement.type())
          || machine.declarationParts().stream()
              .noneMatch(part -> part.automaticInterface() || part.interfaceType().equals(requirement.type()))) {
        throw new IllegalArgumentException(id + ": Missing declared smart interface type/part " + requirement.type());
      }
    }
    // Resource roles/selectors are runtime requirements. A valid recipe may be
    // registered before its machine has the required physical resource parts.
  }

  public static Optional<RecipeCatalog> catalogForFactory(FactoryControllerBlockEntity factory, String threadId, RecipeCatalog base) {
    if (!(factory.getLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    var thread = factory.scheduler().channel(threadId);
    if (published == null || thread == null || base == null || published.catalogs.get(factory.machineId()) != base) {
      return Optional.empty();
    }
    if (base.recipes().size() == 0) {
      return Optional.of(base);
    }
    return contextCatalog(published, factory, base, thread.upgradeSnapshot().recipeModifiers(), true);
  }

  private static Optional<RecipeCatalog> contextCatalog(Published published, StructureService.StructureSubject owner, RecipeCatalog base, List<RecipeModifier> extraRules, boolean reportFailure) {
    var level = (ServerLevel) owner.structureLevel();
    var registration = owner.structureRegistration();
    if (registration == null || !registration.canCommit(level)) {
      return Optional.empty();
    }
    var rules = new ArrayList<>(registration.activeRecipeModifiers(level));
    rules.addAll(extraRules);
    long scriptRevision = MachineControllerScriptDataService.revision(owner);
    rules.addAll(MachineControllerScriptDataService.modifiers(owner));
    if (rules.isEmpty()
        && !published.durationMultiplier
        && scriptRevision == 0
        && published.defaults.equals(published.prepared.defaults())) {
      return Optional.of(base);
    }
    // Untouched controller data keeps the existing shared rule-signature cache.
    var scriptOwner = scriptRevision == 0 ? null : owner;
    var key = new ModifierCatalogKey(owner.structureMachineId(), rules, scriptOwner, scriptRevision);
    var compiler = published.modifiedCatalogs.get(key);
    if (compiler == null) {
      if (published.modifiedCatalogs.size() >= Published.MAX_MODIFIED_CATALOGS) {
        var oldest = published.modifiedCatalogs.keySet().iterator();
        oldest.next();
        oldest.remove();
      }
      compiler = new ModifiedRecipeCatalogCompiler(
          base,
          StructureService.definition(owner.structureMachineId()),
          published.prepared.templates(),
          rules,
          published.durationMultiplier,
          () -> base.isCurrent()
              && (scriptOwner == null || MachineControllerScriptDataService.revision(scriptOwner) == scriptRevision),
          published.defaults);
      published.modifiedCatalogs.put(key, compiler);
    }
    compiler.advance(level.getGameTime(), level.registryAccess());
    if (compiler.failed()) {
      if (reportFailure) {
        throw new UpgradeRecipeCheckHandlers.FailedException();
      }
      return Optional.empty();
    }

    return Optional.ofNullable(compiler.result());
  }

  private record ModifierCatalogKey(
      ResourceLocation machine,
      List<RecipeModifier> rules,
      StructureService.StructureSubject scriptOwner,
      long scriptRevision) {
    private ModifierCatalogKey {
      rules = List.copyOf(rules);
    }
  }

  /**
   * Internal controller-local revocation; paid runs retain their frozen recipe.
   */
  @ApiStatus.Internal
  public static void invalidateControllerScriptData(StructureService.StructureSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Controller recipe context invalidated");
    var published = ACTIVE.get(level.getServer());
    if (published != null) {
      published.modifiedCatalogs.keySet().removeIf(key -> key.scriptOwner() == owner);
    }
  }

  public static Optional<MachineRecipe> definition(MinecraftServer server, ResourceLocation id) {
    var published = current(server);
    return published == null ? Optional.empty() : Optional.ofNullable(published.prepared.definitions().get(id));
  }

  public static Optional<Boolean> recipeBelongsTo(MinecraftServer server, ResourceLocation machineId, ResourceLocation recipeId) {
    var published = current(server);
    if (published == null) {
      return Optional.empty();
    }
    var recipe = published.prepared.definitions().get(recipeId);
    return Optional.of(recipe != null && recipe.machineId().equals(machineId));
  }

  /** Resolves conditional ingredient rules from the original declaration once. */
  @ApiStatus.Internal
  public static Optional<MachineRecipe> resolveIngredientSource(StructureService.StructureSubject owner, RecipeCatalog catalog, ResourceLocation id, List<RecipeModifier> extraRules) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    if (published == null
        || catalog == null
        || !catalog.isCurrent()
        || published.catalogs.get(owner.structureMachineId()) != catalog.root()
        || catalog.recipes().candidate(id.toString()).isEmpty()) {
      return Optional.empty();
    }
    var registration = owner.structureRegistration();
    if (registration == null || !registration.canCommit(level)) {
      return Optional.empty();
    }
    var structuralRules = registration.activeRecipeModifiers(level);
    var context = catalog.contextRules();
    if (owner instanceof FactoryControllerBlockEntity) {
      if (context.size() < structuralRules.size()
          || !context.subList(0, structuralRules.size()).equals(structuralRules)) {
        return Optional.empty();
      }
    } else {
      var expected = new ArrayList<>(structuralRules);
      if (owner instanceof MachineControllerBlockEntity ordinary) {
        var upgrades = ordinary.upgradeSnapshot();
        if (!ordinary.upgradeSnapshotCurrent(upgrades)) {
          return Optional.empty();
        }
        expected.addAll(upgrades.recipeModifiers());
      }
      expected.addAll(MachineControllerScriptDataService.modifiers(owner));
      if (!context.equals(expected)) {
        return Optional.empty();
      }
    }
    var template = published.prepared.templates().get(id);
    if (template == null) {
      return Optional.empty();
    }
    var rules = new ArrayList<>(context);
    rules.addAll(extraRules);
    var result = template.resolve(rules, level.registryAccess(), published.durationMultiplier, published.defaults);
    return catalog.isCurrent() && result.machineId().equals(owner.structureMachineId()) ? Optional.of(result) : Optional
        .empty();
  }

  static Optional<MachineRecipe> definitionForStart(MachineControllerBlockEntity controller, RecipeStartPlan plan) {
    return definitionForStart((StructureService.StructureSubject) controller, plan);
  }

  public static Optional<MachineRecipe> definitionForStart(StructureService.StructureSubject owner, RecipeStartPlan plan) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    var id = ResourceLocation.tryParse(plan.recipe().id());
    if (published == null
        || id == null
        || published.catalogs.get(owner.structureMachineId()) != plan.catalog().root()
        || !plan.catalog().isCurrent()
        || !plan.inputPreparationCurrent()
        || plan.catalog().recipes().candidate(plan.recipe().id()).orElse(null) != plan.catalogCandidate()) {
      return Optional.empty();
    }
    var registration = owner.structureRegistration();
    if (registration == null || !registration.canCommit(level)) {
      return Optional.empty();
    }
    var structuralRules = registration.activeRecipeModifiers(level);
    var context = plan.catalog().contextRules();
    if (owner instanceof FactoryControllerBlockEntity) {
      if (context.size() < structuralRules.size()
          || !context.subList(0, structuralRules.size()).equals(structuralRules)) {
        return Optional.empty();
      }
    } else if (owner instanceof MachineControllerBlockEntity ordinary) {
      var upgrades = ordinary.upgradeSnapshot();
      var expected = new ArrayList<>(structuralRules);
      expected.addAll(upgrades.recipeModifiers());
      expected.addAll(MachineControllerScriptDataService.modifiers(owner));
      if (!ordinary.upgradeSnapshotCurrent(upgrades) || !context.equals(expected)) {
        return Optional.empty();
      }
    } else {
      var expected = new ArrayList<>(structuralRules);
      expected.addAll(MachineControllerScriptDataService.modifiers(owner));
      if (!context.equals(expected)) {
        return Optional.empty();
      }
    }
    var definition = plan.preparedInputDefinition().orElseGet(() -> plan.catalog().definition(id.toString()));
    if (definition == null && plan.catalog().root() == plan.catalog()) {
      definition = published.prepared.definitions().get(id);
    }
    return definition != null && definition.machineId().equals(owner.structureMachineId()) ? Optional
        .of(definition) : Optional.empty();
  }

  private static Published current(MinecraftServer server) {
    ServerThreadGuard.require(server, "Machine recipe catalog accessed");
    var manager = server.getRecipeManager();
    var prepared = PREPARED.get(manager);
    if (prepared == null
        || prepared.structureEpoch() != StructureService.definitionEpoch()
        || prepared.recipeView() != manager.getRecipes()) {
      return null;
    }
    var published = ACTIVE.get(server);
    boolean alreadyPublished = published != null && published.manager == manager && published.prepared == prepared;
    // Declaration revisions guard new publication, not the authority of a catalog
    // already installed before a later script generation failed validation.
    if (!alreadyPublished
        && (!RecipeAdapterScriptSources.isCurrent(prepared.recipeAdapterScriptRevision())
            || !RecipeScriptCallbacks.isCurrent(prepared.itemCallbacks()))) {
      return null;
    }
    boolean durationMultiplier = MachineConfig.durationMultiplierEnabled();
    var defaults = MachineRecipeJson.currentDefaults();
    if (alreadyPublished && published.durationMultiplier == durationMultiplier && published.defaults.equals(defaults)) {
      return published;
    }
    var next = new Published(server, manager, prepared, durationMultiplier, defaults);
    if (!alreadyPublished) {
      RecipeScriptCallbacks.install(prepared.itemCallbacks());
    }
    ACTIVE.put(server, next);
    return next;
  }

  private static final class Published {
    static final int MAX_MODIFIED_CATALOGS = 16;
    final MinecraftServer server;
    final RecipeManager manager;
    final Prepared prepared;
    final boolean durationMultiplier;
    final MachineRecipeJson.Defaults defaults;
    final Map<ResourceLocation, RecipeCatalog> catalogs;
    final LinkedHashMap<ModifierCatalogKey, ModifiedRecipeCatalogCompiler> modifiedCatalogs = new LinkedHashMap<>(
        16,
        0.75F,
        true);

    Published(MinecraftServer server, RecipeManager manager, Prepared prepared, boolean durationMultiplier, MachineRecipeJson.Defaults defaults) {
      this.server = server;
      this.manager = manager;
      this.prepared = prepared;
      this.durationMultiplier = durationMultiplier;
      this.defaults = defaults;
      var values = new HashMap<ResourceLocation, RecipeCatalog>();
      prepared.catalogs().forEach((machine, catalog) -> values.put(machine, catalog.publish(this::isCurrent)));
      catalogs = Map.copyOf(values);
    }

    boolean isCurrent() {
      ServerThreadGuard.require(server, "Machine recipe catalog accessed");
      return ACTIVE.get(server) == this
          && PREPARED.get(manager) == prepared
          && server.getRecipeManager() == manager
          && manager.getRecipes() == prepared.recipeView()
          && MachineConfig.durationMultiplierEnabled() == durationMultiplier
          && MachineRecipeJson.currentDefaults().equals(defaults)
          && StructureService.definitionEpoch() == prepared.structureEpoch();
    }
  }

  @SubscribeEvent
  public static void addReloadListener(AddReloadListenerEvent event) {
    event.addListener(new MachineRecipeReloadListener(event.getServerResources().getRecipeManager(), nativeSources));
  }

  @SubscribeEvent
  public static void started(ServerStartedEvent event) {
    current(event.getServer());
  }

  @SubscribeEvent
  public static void sync(OnDatapackSyncEvent event) {
    current(event.getPlayerList().getServer());
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    ACTIVE.remove(event.getServer());
    PREPARED.clear();
    nativeSources = new MachineRecipeReloadListener.SourceCache();
    RecipeScriptSources.clear();
    RecipeScriptCallbacks.reset();
    RecipeAdapterScriptSources.clear();
    ChemicalResourceExecution.clear();
    FluidTickResourceExecution.clear();
    TriggeredItemExecution.clear();
  }
}
