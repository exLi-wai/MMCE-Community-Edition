package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.integration.kubejs.MachineItemRecipeKubeJSSources;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRecipeChecks;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Validated candidates are staged by prospective RecipeManager; only current
 * resources gain live authority.
 */
public final class MachineRecipeService {
  private static final Map<RecipeManager, Prepared> PREPARED = new IdentityHashMap<>();
  private static final Map<MinecraftServer, Published> ACTIVE = new IdentityHashMap<>();

  private record Prepared(
      long structureEpoch,
      Collection<RecipeHolder<?>> recipeView,
      Map<ResourceLocation, CompiledRecipeSet<ItemResourceKey>> candidates,
      Map<ResourceLocation, MachineRecipe> definitions,
      Map<ResourceLocation, RecipeModifierTemplate> templates) {
  }

  private MachineRecipeService() {
  }

  static void stage(RecipeManager manager, Map<ResourceLocation, MachineRecipe> expected, Map<ResourceLocation, JsonElement> sources, HolderLookup.Provider registries) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null) {
      ServerThreadGuard.require(server, "Machine recipe catalog accessed");
    }
    var actual = new LinkedHashMap<ResourceLocation, MachineRecipe>();
    for (var holder : manager.getAllRecipesFor(MachineRecipeRegistries.ITEM_TYPE.get())) {
      boolean nativeMachineRecipe = holder.id().getPath().startsWith("mmcece/");
      boolean kubejsMachineRecipe = holder.id().getNamespace().equals("kubejs") && sources.containsKey(holder.id());
      if (!nativeMachineRecipe && !kubejsMachineRecipe) {
        throw new IllegalArgumentException(holder.id() + ": Machine recipes must use recipe/mmcece/*.json");
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
    var candidates = new HashMap<ResourceLocation, List<RecipeCandidate<ItemResourceKey>>>();
    var effective = new LinkedHashMap<ResourceLocation, MachineRecipe>();
    var templates = new LinkedHashMap<ResourceLocation, RecipeModifierTemplate>();
    for (var entry : expected.entrySet()) {
      var original = actual.get(entry.getKey());
      if (!entry.getValue().sameDefinition(original)) {
        throw new IllegalArgumentException(entry.getKey() + ": Native recipe differs from validated resource");
      }
      var machine = StructureService.definition(original.machineId());
      if (machine == null) {
        throw new IllegalArgumentException(entry.getKey() + ": Unknown machine " + original.machineId());
      }
      var recipe = original;
      var source = sources.get(entry.getKey());
      if (source == null || !source.isJsonObject()) {
        throw new IllegalArgumentException(entry.getKey() + ": Missing original recipe declaration");
      }
      var template = new RecipeModifierTemplate(source.getAsJsonObject(), machine.recipeModifiers());
      templates.put(entry.getKey(), template);
      if (!machine.recipeModifiers().isEmpty()) {
        try {
          var resolved = template.resolve(List.of(), registries);
          if (!resolved.sameDefinition(original)) {
            recipe = resolved;
          }
        } catch (RuntimeException invalid) {
          throw new IllegalArgumentException(entry.getKey() + ": Invalid machine recipe modifiers", invalid);
        }
      }
      validatePorts(entry.getKey(), recipe, machine);
      candidates.computeIfAbsent(recipe.machineId(), ignored -> new ArrayList<>())
          .add(recipe.compile(entry.getKey(), registries));
      effective.put(entry.getKey(), recipe);
    }
    var frozen = new HashMap<ResourceLocation, CompiledRecipeSet<ItemResourceKey>>();
    candidates.forEach((machine, values) -> {
      frozen.put(machine, new CompiledRecipeSet<>(values)); // Compile every machine before any publication.
    });
    boolean changed = effective.entrySet().stream().anyMatch(entry -> entry.getValue() != actual.get(entry.getKey()));
    if (changed) {
      var replacements = new ArrayList<RecipeHolder<?>>();
      for (var holder : manager.getRecipes()) {
        var replacement = effective.get(holder.id());
        replacements.add(replacement == null ? holder : new RecipeHolder<>(holder.id(), replacement));
      }
      // This is the prospective reload manager. Preserve all other recipe types and
      // publish once after validation.
      manager.replaceRecipes(replacements);
    }
    var candidate = new Prepared(
        StructureService.definitionEpoch(),
        manager.getRecipes(),
        Map.copyOf(frozen),
        Map.copyOf(effective),
        Map.copyOf(templates));
    PREPARED.entrySet().removeIf(
        entry -> entry.getKey() != manager
            && ACTIVE.values().stream().noneMatch(active -> active.manager == entry.getKey()));
    PREPARED.put(manager, candidate);
  }

  public static Optional<ItemRecipeCatalog> catalogFor(MachineControllerBlockEntity controller) {
    return catalogFor((StructureService.StructureSubject) controller);
  }

  public static Optional<ItemRecipeCatalog> catalogFor(StructureService.StructureSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    var base = published == null ? null : published.catalogs.get(owner.structureMachineId());
    if (base == null || owner instanceof FactoryControllerBlockEntity) {
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
    if (StructureService.definition(owner.structureMachineId()).structureModifiers().isEmpty()) {
      return Optional.of(base);
    }

    return contextCatalog(published, owner, base, List.of(), false);
  }

  static void validatePorts(ResourceLocation id, MachineRecipe recipe, MachineDefinition machine) {
    for (var requirement : recipe.interfaceInputs()) {
      if (!machine.interfaceTypes().containsKey(requirement.type())
          || machine.parts().stream()
              .noneMatch(part -> part.automaticInterface() || part.interfaceType().equals(requirement.type()))) {
        throw new IllegalArgumentException(id + ": Missing declared smart interface type/part " + requirement.type());
      }
    }
    for (var input : recipe.inputs()) {
      requireSelectorPort(id, machine, PortKind.ITEM_INPUT, input.selector());
    }
    for (var output : recipe.outputs()) {
      requireSelectorPort(id, machine, PortKind.ITEM_OUTPUT, output.selector());
    }
    if (!recipe.fluidInputs().isEmpty()) {
      requireSelectorPort(id, machine, PortKind.FLUID_INPUT, recipe.selectors().fluidInput());
    }
    if (!recipe.fluidOutputs().isEmpty()) {
      requireSelectorPort(id, machine, PortKind.FLUID_OUTPUT, recipe.selectors().fluidOutput());
    }
    if (recipe.energyPerTick() > 0) {
      requireSelectorPort(id, machine, PortKind.ENERGY_INPUT, recipe.selectors().energyInput());
    }
    if (!recipe.fluidPerTick().isEmpty()) {
      requireSelectorPort(id, machine, PortKind.FLUID_INPUT, recipe.selectors().fluidPerTick());
    }
    if (recipe.energyPerTick() > 0
        && machine.parts().stream().noneMatch(part -> part.rule().port() == PortKind.ENERGY_INPUT)) {
      throw new IllegalArgumentException(id + ": FE/t recipes require an energy input port");
    }
    if ((!recipe.fluidPerTick().isEmpty() || !recipe.fluidInputs().isEmpty())
        && machine.parts().stream().noneMatch(part -> part.rule().port() == PortKind.FLUID_INPUT)) {
      throw new IllegalArgumentException(id + ": Fluid input recipes require a fluid input port");
    }
    if (!recipe.fluidOutputs().isEmpty()
        && machine.parts().stream().noneMatch(part -> part.rule().port() == PortKind.FLUID_OUTPUT)) {
      throw new IllegalArgumentException(id + ": Fluid output recipes require a fluid output port");
    }
  }

  public static Optional<ItemRecipeCatalog> catalogForFactory(FactoryControllerBlockEntity factory, String threadId, ItemRecipeCatalog base) {
    if (!(factory.getLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    var thread = factory.scheduler().channel(threadId);
    if (published == null || thread == null || base == null || published.catalogs.get(factory.machineId()) != base) {
      return Optional.empty();
    }
    return contextCatalog(published, factory, base, thread.upgradeSnapshot().recipeModifiers(), true);
  }

  private static void requireSelectorPort(ResourceLocation recipe, MachineDefinition machine, PortKind kind, String selector) {
    if (machine.parts().stream().noneMatch(part -> part.rule().port() == kind && part.selector().equals(selector))) {
      throw new IllegalArgumentException(recipe + ": No " + kind + " port in selector '" + selector + "'");
    }
  }

  private static Optional<ItemRecipeCatalog> contextCatalog(Published published, StructureService.StructureSubject owner, ItemRecipeCatalog base, List<RecipeModifier> extraRules, boolean reportFailure) {
    var level = (ServerLevel) owner.structureLevel();
    var registration = owner.structureRegistration();
    if (registration == null || !registration.canCommit(level)) {
      return Optional.empty();
    }
    var rules = new ArrayList<>(registration.activeRecipeModifiers(level));
    rules.addAll(extraRules);
    if (rules.isEmpty()) {
      return Optional.of(base);
    }
    var key = new ModifierCatalogKey(owner.structureMachineId(), rules);
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
          rules);
      published.modifiedCatalogs.put(key, compiler);
    }
    compiler.advance(level.getGameTime(), level.registryAccess());
    if (compiler.failed()) {
      if (reportFailure) {
        throw new FactoryUpgradeRecipeChecks.FailedException();
      }
      return Optional.empty();
    }

    return Optional.ofNullable(compiler.result());
  }

  private record ModifierCatalogKey(ResourceLocation machine, List<RecipeModifier> rules) {
    private ModifierCatalogKey {
      rules = List.copyOf(rules);
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

  static Optional<MachineRecipe> definitionForStart(MachineControllerBlockEntity controller, ItemRecipePlan plan) {
    return definitionForStart((StructureService.StructureSubject) controller, plan);
  }

  public static Optional<MachineRecipe> definitionForStart(StructureService.StructureSubject owner, ItemRecipePlan plan) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    var published = current(level.getServer());
    var id = ResourceLocation.tryParse(plan.recipe().id());
    if (published == null
        || id == null
        || published.catalogs.get(owner.structureMachineId()) != plan.catalog().root()
        || !plan.catalog().isCurrent()
        || plan.catalog().recipes().candidate(plan.recipe().id()).orElse(null) != plan.recipe()) {
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
      if (!ordinary.upgradeSnapshotCurrent(upgrades) || !context.equals(expected)) {
        return Optional.empty();
      }
    } else if (!context.equals(structuralRules)) {
      return Optional.empty();
    }
    var definition = plan.catalog().definition(id.toString());
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
    if (published != null && published.manager == manager && published.prepared == prepared) {
      return published;
    }
    var next = new Published(server, manager, prepared);
    ACTIVE.put(server, next);
    return next;
  }

  private static final class Published {
    static final int MAX_MODIFIED_CATALOGS = 16;
    final MinecraftServer server;
    final RecipeManager manager;
    final Prepared prepared;
    final Map<ResourceLocation, ItemRecipeCatalog> catalogs;
    final LinkedHashMap<ModifierCatalogKey, ModifiedRecipeCatalogCompiler> modifiedCatalogs = new LinkedHashMap<>(
        16,
        0.75F,
        true);

    Published(MinecraftServer server, RecipeManager manager, Prepared prepared) {
      this.server = server;
      this.manager = manager;
      this.prepared = prepared;
      var values = new HashMap<ResourceLocation, ItemRecipeCatalog>();
      prepared.candidates().forEach((machine, recipes) -> {
        var fluids = new HashMap<String, List<FluidStack>>();
        var outputs = new HashMap<String, List<FluidStack>>();
        var definitions = new HashMap<String, MachineRecipe>();
        var selectors = new HashMap<String, RecipeSelectors>();
        prepared.definitions().forEach((id, definition) -> {
          if (definition.machineId().equals(machine)) {
            fluids.put(id.toString(), definition.fluidInputs());
            outputs.put(id.toString(), definition.fluidOutputs());
            definitions.put(id.toString(), definition);
            selectors.put(id.toString(), definition.selectors());
          }
        });
        values.put(
            machine,
            new ItemRecipeCatalog(
                machine,
                recipes,
                fluids,
                outputs,
                this::isCurrent,
                null,
                definitions,
                List.of(),
                selectors));
      });
      catalogs = Map.copyOf(values);
    }

    boolean isCurrent() {
      ServerThreadGuard.require(server, "Machine recipe catalog accessed");
      return ACTIVE.get(server) == this
          && PREPARED.get(manager) == prepared
          && server.getRecipeManager() == manager
          && manager.getRecipes() == prepared.recipeView()
          && StructureService.definitionEpoch() == prepared.structureEpoch();
    }
  }

  @SubscribeEvent
  public static void addReloadListener(AddReloadListenerEvent event) {
    event.addListener(new MachineRecipeReloadListener(event.getServerResources().getRecipeManager()));
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
    MachineItemRecipeKubeJSSources.clear();
  }
}
