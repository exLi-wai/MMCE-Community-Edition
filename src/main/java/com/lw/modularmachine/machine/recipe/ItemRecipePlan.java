package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.factory.UpgradeRecipeChecks;

import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.factory.UpgradeBusOwner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only start admission: deductions are not applied and placements do not
 * reserve future output.
 */
public final class ItemRecipePlan {
  private final RecipeExecutionTicket source;
  private final ItemRecipeCatalog catalog;
  private final RecipeCandidate<ItemResourceKey> recipe;
  private final List<Deduction> deductions;
  private final List<Placement> placements;
  private final long inputChanceSeed;
  private UpgradeRecipeChecks.Scope factoryScope;
  private UpgradeRecipeChecks.Session factoryChecks;
  private FactoryUpgradeStartCosts.Preparation factoryStartup;
  private int startupInstances;
  private ParallelRecipeAdmission parallelAdmission;
  private int parallelRequest;

  ItemRecipePlan(RecipeExecutionTicket source, ItemRecipeCatalog catalog, RecipeCandidate<ItemResourceKey> recipe, List<Deduction> deductions, List<Placement> placements, long inputChanceSeed, UpgradeRecipeChecks.Scope factoryScope) {
    this.source = source;
    this.catalog = catalog;
    this.recipe = recipe;
    this.deductions = List.copyOf(deductions);
    this.placements = List.copyOf(placements);
    if (inputChanceSeed < 0) {
      throw new IllegalArgumentException("Negative input chance seed");
    }
    this.inputChanceSeed = inputChanceSeed;
    this.factoryScope = factoryScope;
  }

  public long inputChanceSeed() {
    return inputChanceSeed;
  }

  static long newInputChanceSeed() {
    long seed = RandomSource.create().nextLong() & Long.MAX_VALUE;
    return seed == 0 ? 1 : seed;
  }

  static <K> Map<K, Long> sampledInputs(RecipeCandidate<K> recipe, long seed, int instances) {
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid input chance instances");
    }
    var result = new HashMap<K, Long>();
    for (int instance = 0; instance < instances; instance++) {
      var random = RandomSource.create(inputChanceSeedForInstance(seed, instance));
      for (var input : recipe.inputs()) {
        if (input.chance() >= 1.0D || input.chance() > 0.0D && random.nextDouble() < input.chance()) {
          result.merge(input.key(), input.count(), Math::addExact);
        }
      }
    }
    return Map.copyOf(result);
  }

  static long inputChanceSeedForInstance(long seed, int instance) {
    if (instance < 0) {
      throw new IllegalArgumentException("Negative recipe instance");
    }
    if (instance == 0) {
      return seed;
    }
    long mixed = seed + 0x9E3779B97F4A7C15L * instance;
    mixed = (mixed ^ mixed >>> 30) * 0xBF58476D1CE4E5B9L;
    mixed = (mixed ^ mixed >>> 27) * 0x94D049BB133111EBL;
    mixed ^= mixed >>> 31;
    return mixed & Long.MAX_VALUE;
  }

  public UpgradeRecipeChecks.Result advanceFactoryChecks(UpgradeBusOwner factory, String threadId, int budget) {
    source.requireServerThread();
    if (source.owner() != factory
        || !source.dependenciesCurrent()
        || !catalog.isCurrent()
        || !modifierContextCurrent(factory, threadId)) {
      return new UpgradeRecipeChecks.Result(UpgradeRecipeChecks.Status.STALE, 0);
    }
    if (factoryScope == null) {
      factoryScope = UpgradeRecipeChecks.capture(factory, threadId);
    }
    if (!factoryScope.matches(factory, threadId) || !factoryScope.current()) {
      return new UpgradeRecipeChecks.Result(UpgradeRecipeChecks.Status.STALE, 0);
    }
    if (factoryChecks == null) {
      factoryChecks = factoryScope.begin(this);
    }
    var result = factoryChecks.advance(budget);

    return source.dependenciesCurrent() && catalog.isCurrent() ? result : new UpgradeRecipeChecks.Result(
        UpgradeRecipeChecks.Status.STALE,
        result.workUsed());
  }

  public boolean factoryAdmissionCurrent(UpgradeBusOwner factory, String threadId) {
    source.requireServerThread();

    return source.owner() == factory
        && factoryChecks != null
        && factoryChecks.admitted(factory, threadId)
        && source.dependenciesCurrent()
        && catalog.isCurrent()
        && modifierContextCurrent(factory, threadId);
  }

  private boolean modifierContextCurrent(UpgradeBusOwner factory, String threadId) {
    var thread = factory.upgradeStartState(threadId);
    var registration = factory.structureRegistration();
    if (thread == null || registration == null || !registration.canCommit(source.level())) {
      return false;
    }
    var structural = registration.activeRecipeModifiers(source.level());
    var upgrades = thread.upgradeSnapshot().recipeModifiers();
    var actual = catalog.contextRules();
    var expected = new ArrayList<>(structural);
    expected.addAll(upgrades);
    expected.addAll(MachineControllerScriptDataService.modifiers(factory));

    return actual.equals(expected);
  }

  public FactoryUpgradeStartCosts.Preparation prepareFactoryStartup(UpgradeBusOwner factory, String threadId, int instances) {
    source.requireServerThread();
    if (source.owner() != factory) {
      throw new IllegalArgumentException("Startup plan belongs to a different controller");
    }
    var definition = MachineRecipeService.definitionForStart(factory, this).orElse(null);
    if (definition == null || instances < 1 || instances > 1 && !definition.parallelized()) {
      throw new IllegalArgumentException("Recipe does not allow the requested parallel instances");
    }
    if (factoryStartup == null || startupInstances != instances) {
      factoryStartup = FactoryUpgradeStartCosts
          .prepare(factory, threadId, ResourceLocation.parse(recipe.id()), instances);
      startupInstances = instances;
    }

    return factoryStartup;
  }

  public RecipeCandidate<ItemResourceKey> recipe() {
    return recipe;
  }

  public List<Deduction> deductions() {
    return deductions;
  }

  public List<Placement> placements() {
    return placements;
  }

  ItemRecipePlan scaled(int instances) {
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid parallel instances");
    }
    if (instances == 1) {
      return this;
    }
    var needed = new HashMap<>(sampledInputs(recipe, inputChanceSeed, instances));
    var scaledDeductions = new ArrayList<Deduction>();
    for (var port : source.ports()) {
      if (port.kind() != PortKind.ITEM_INPUT) {
        continue;
      }
      var stacks = port.items();
      for (int slot = 0; slot < stacks.size(); slot++) {
        var stack = stacks.get(slot);
        if (stack.isEmpty()) {
          continue;
        }
        var key = ItemResourceKey.of(stack).withSelector(port.selector());
        var expected = inputKey(recipe, key);
        int take = (int) Math.min(stack.getCount(), needed.getOrDefault(expected, 0L));
        if (take > 0) {
          scaledDeductions.add(new Deduction(new ItemSlotRef(port.partId(), slot), key, take));
          needed.put(expected, needed.get(expected) - take);
        }
      }
    }
    if (needed.values().stream().anyMatch(amount -> amount != 0)) {
      throw new IllegalStateException("Parallel input allocation exceeds physical resources");
    }

    return new ItemRecipePlan(source, catalog, recipe, scaledDeductions, placements, inputChanceSeed, factoryScope);
  }

  static ItemRecipePlan combineInputs(List<ItemRecipePlan> plans) {
    if (plans == null || plans.isEmpty()) {
      throw new IllegalArgumentException("No parallel recipe plans");
    }
    var first = plans.getFirst();
    var needed = new HashMap<ItemResourceKey, Long>();
    for (int index = 0; index < plans.size(); index++) {
      var plan = plans.get(index);
      if (plan.source.owner() != first.source.owner()
          || plan.catalog != first.catalog
          || !plan.recipe.id().equals(first.recipe.id())) {
        throw new IllegalArgumentException("Parallel plans do not share one recipe owner");
      }
      sampledInputs(plan.recipe, inputChanceSeedForInstance(plan.inputChanceSeed, index), 1)
          .forEach((key, amount) -> needed.merge(key, amount, Math::addExact));
    }
    var combined = new ArrayList<Deduction>();
    for (var port : first.source.ports()) {
      if (port.kind() != PortKind.ITEM_INPUT) {
        continue;
      }
      for (int slot = 0; slot < port.items().size(); slot++) {
        var stack = port.items().get(slot);
        if (stack.isEmpty()) {
          continue;
        }
        var key = ItemResourceKey.of(stack).withSelector(port.selector());
        var expected = inputKey(first.recipe, key);
        int amount = (int) Math.min(needed.getOrDefault(expected, 0L), stack.getCount());
        if (amount > 0) {
          combined.add(new Deduction(new ItemSlotRef(port.partId(), slot), key, amount));
          needed.put(expected, needed.get(expected) - amount);
        }
      }
    }
    if (needed.values().stream().anyMatch(amount -> amount != 0)) {
      throw new IllegalStateException("Parallel input chance allocation exceeds physical resources");
    }

    return new ItemRecipePlan(
        first.source,
        first.catalog,
        first.recipe,
        combined,
        first.placements,
        first.inputChanceSeed,
        first.factoryScope);
  }

  public int prepareParallel(MachineRecipe definition, int limit, int budget) {
    limit = definition.parallelized() ? limit : Math.min(limit, 1);
    if (parallelAdmission == null || parallelRequest != limit) {
      parallelAdmission = new ParallelRecipeAdmission(
          this,
          definition,
          limit,
          source.owner() instanceof MachineControllerBlockEntity);
      parallelRequest = limit;
    }

    return parallelAdmission.advance(budget);
  }

  public int maxRepeatableInputs() {
    var available = new HashMap<ItemResourceKey, Long>();
    var required = new HashMap<ItemResourceKey, Long>();
    for (var input : recipe.inputs()) {
      if (input.chance() > 0) {
        required.merge(input.key(), input.count(), Math::addExact);
      }
    }
    for (var port : source.ports()) {
      if (port.kind() == PortKind.ITEM_INPUT) {
        for (var stack : port.items()) {
          if (!stack.isEmpty()) {
            available.merge(
                inputKey(recipe, ItemResourceKey.of(stack).withSelector(port.selector())),
                (long) stack.getCount(),
                Math::addExact);
          }
        }
      }
    }
    long result = Integer.MAX_VALUE;
    for (var entry : required.entrySet()) {
      result = Math.min(result, available.getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }

    return required.isEmpty() ? Integer.MAX_VALUE : (int) result;
  }

  /** Lookup identity only; deductions must keep the exact physical slot key. */
  @ApiStatus.Internal
  public static ItemResourceKey inputKey(RecipeCandidate<ItemResourceKey> candidate, ItemResourceKey physical) {
    if (candidate.inputs().size() == 1 && candidate.inputs().getFirst().key().ignoresComponents()) {
      return physical.ignoringComponents();
    }
    return physical;
  }

  public int maxRepeatableOutputs() {
    var required = new HashMap<ItemResourceKey, Long>();
    for (var output : recipe.outputs()) {
      required.merge(output.key(), output.count(), Math::addExact);
    }
    long result = Integer.MAX_VALUE;
    for (var entry : required.entrySet()) {
      long capacity = 0;
      for (var port : source.ports()) {
        if (port.kind() != PortKind.ITEM_OUTPUT || !port.selector().equals(entry.getKey().selector())) {
          continue;
        }
        var stacks = port.items();
        for (int slot = 0; slot < stacks.size(); slot++) {
          var stack = stacks.get(slot);
          if (stack.isEmpty() || entry.getKey().matches(stack)) {
            capacity += Math
                .max(0, Math.min(port.slotLimits().get(slot), entry.getKey().maxStackSize()) - stack.getCount());
          }
        }
      }
      result = Math.min(result, capacity / entry.getValue());
    }

    return (int) result;
  }

  /**
   * The server owner must supply its currently published catalog; this still
   * performs no transaction.
   */
  public boolean validateForStart(ItemRecipeCatalog currentCatalog) {
    source.requireServerThread();
    return currentCatalog == catalog && checkedTicket().isPresent();
  }

  public boolean validateForStart() {
    return validateForStart(catalog);
  }

  Optional<RecipeExecutionTicket> checkedTicket() {
    source.requireServerThread();
    if (!catalog.isCurrent()) {
      return Optional.empty();
    }
    var fresh = source.refreshIfUnchanged().filter(RecipeCommitGuard::validate);
    return catalog.isCurrent() ? fresh : Optional.empty();
  }

  RecipeExecutionTicket source() {
    return source;
  }

  ItemRecipeCatalog catalog() {
    return catalog;
  }

  public AtomicRecipeStarter.Result start() {
    return AtomicRecipeStarter.start(this);
  }

  public record Deduction(ItemSlotRef slot, ItemResourceKey key, int count) {
    public Deduction {
      if (slot == null || key == null || key.ignoresComponents() || count <= 0) {
        throw new IllegalArgumentException("Invalid slot deduction");
      }
    }
  }

  public record Placement(ItemSlotRef slot, ItemResourceKey key, int count) {
    public Placement {
      if (slot == null || key == null || key.ignoresComponents() || count <= 0 || count > key.maxStackSize()) {
        throw new IllegalArgumentException("Invalid slot placement");
      }
    }
  }
}
