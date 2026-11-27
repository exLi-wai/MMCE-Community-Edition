package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.ResourceSnapshot;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntToLongFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.ApiStatus;
import java.util.HashSet;
import java.util.Objects;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;

/**
 * Read-only start admission: deductions are not applied and placements do not
 * reserve future output.
 */
@ApiStatus.Internal
public final class RecipeStartPlan {
  private final RecipeExecutionTicket source;
  private final RecipeCatalog catalog;
  private final RecipeCandidate<ItemResourceKey> recipe;
  private final List<Deduction> deductions;
  private final List<Placement> placements;
  private final long inputChanceSeed;
  private UpgradeRecipeCheckHandlers.Scope factoryScope;
  private UpgradeRecipeCheckHandlers.Session factoryChecks;
  private UpgradeStartCosts.Preparation factoryStartup;
  private int startupInstances;
  private ParallelRecipeAdmission parallelAdmission;
  private UpgradeSnapshot parallelUpgrades;
  private int parallelRequest;
  private RecipeStartOutputAdmission startOutputAdmission;
  private RecipeResourceExecutionRegistry.PaidStartPlanning paidStartPlanning;
  private MachineRecipe inputPreparedDefinition;
  private RecipeItemInputAllocation inputAllocation;
  private Map<ItemResourceKey, Long> searchInputCounts;
  // Bound by ordinary inputs, then tightened when a larger tool/fuel probe fails.
  private int inputCapacity = -1;
  private int provenPaymentLimit;
  private int outputCapacity = -1;
  private RecipeCandidate<ItemResourceKey> originalCandidate;
  private long inputPreparedEpoch = -1;
  private BatchInputPreparation batchInputs;
  private String outputPreparationThread;
  private PreparedRecipeOutputs outputPreparation;
  private int parallelBudgetRemaining;
  private int preparationLimit = Integer.MAX_VALUE;
  private RecipePreparationHandlers.Session postChecks;
  private final long preparationEpoch = RecipePreparationHandlers.epoch();

  RecipeStartPlan(RecipeExecutionTicket source, RecipeCatalog catalog, RecipeCandidate<ItemResourceKey> recipe, List<Deduction> deductions, List<Placement> placements, long inputChanceSeed, UpgradeRecipeCheckHandlers.Scope factoryScope) {
    this.source = source;
    this.catalog = catalog;
    this.recipe = recipe;
    this.deductions = RecipeItemInputAllocation.freezeDeductions(deductions);
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

  public RecipeStartPlan withPreparationLimit(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("Positive recipe preparation limit required");
    }
    preparationLimit = Math.min(preparationLimit, limit);
    return this;
  }

  public RecipeStartPlan withOutputPreparationContext(String thread) {
    if (!Objects.equals(outputPreparationThread, thread)) {
      parallelAdmission = null;
      parallelUpgrades = null;
      outputPreparation = null;
      paidStartPlanning = null;
      batchInputs = null;
    }
    outputPreparationThread = thread;
    return this;
  }

  /** Internal read-only admission assembled by the resource search. */
  public static RecipeStartPlan prepared(RecipeExecutionTicket source, RecipeCatalog catalog, RecipeCandidate<ItemResourceKey> recipe, List<Deduction> deductions, List<Placement> placements, long inputChanceSeed, UpgradeRecipeCheckHandlers.Scope factoryScope) {
    return new RecipeStartPlan(source, catalog, recipe, deductions, placements, inputChanceSeed, factoryScope);
  }

  /** The search has tallied every relevant input key for this ticket's group. */
  public static RecipeStartPlan prepared(RecipeExecutionTicket source, RecipeCatalog catalog, RecipeCandidate<ItemResourceKey> recipe, List<Deduction> deductions, List<Placement> placements, long inputChanceSeed, UpgradeRecipeCheckHandlers.Scope factoryScope, ResourceSnapshot<ItemResourceKey> snapshot) {
    var plan = prepared(source, catalog, recipe, deductions, placements, inputChanceSeed, factoryScope);
    plan.searchInputCounts = snapshot.inputs();
    return plan;
  }

  /**
   * Internal search result: transformed inputs are planned once against one
   * callback generation.
   */
  public RecipeStartPlan withPreparedInputs(MachineRecipe definition, RecipeCandidate<ItemResourceKey> original, long epoch) {
    source.requireServerThread();
    if (inputPreparedDefinition != null
        || definition == null
        || original == null
        || !original.id().equals(recipe.id())
        || !definition.machineId().equals(catalog.machineId())
        || catalog.recipes().candidate(original.id()).orElse(null) != original
        || !definition.compile(ResourceLocation.parse(recipe.id()), source.level().registryAccess()).equals(recipe)
        || !RecipeItemCallbackService.isCurrent(epoch)) {
      throw new IllegalArgumentException("Invalid prepared input recipe override");
    }
    inputPreparedDefinition = definition;
    originalCandidate = original;
    inputPreparedEpoch = epoch;
    provenPaymentLimit = 0;
    inputCapacity = -1;
    outputCapacity = -1;
    return this;
  }

  RecipeCandidate<ItemResourceKey> catalogCandidate() {
    return originalCandidate == null ? recipe : originalCandidate;
  }

  public boolean inputPreparationCurrent() {
    return inputPreparedEpoch < 0 || RecipeItemCallbackService.isCurrent(inputPreparedEpoch);
  }

  public RecipeStartPlan withInputAllocation(RecipeItemInputAllocation allocation) {
    inputAllocation = allocation;
    return this;
  }

  public RecipeItemInputAllocation inputAllocation() {
    return inputAllocation;
  }

  Optional<MachineRecipe> preparedInputDefinition() {
    return inputPreparationCurrent() ? Optional.ofNullable(inputPreparedDefinition) : Optional.empty();
  }

  public static long newInputChanceSeed() {
    long seed = RandomSource.create().nextLong() & Long.MAX_VALUE;
    return seed == 0 ? 1 : seed;
  }

  public static <K> Map<K, Long> sampledInputs(RecipeCandidate<K> recipe, long seed, int instances) {
    var sampled = sampledInputRows(recipe, seed, instances);
    return sampledByKey(recipe, sampled);
  }

  private static <K> Map<K, Long> sampledByKey(RecipeCandidate<K> recipe, long[] sampled) {
    var result = new HashMap<K, Long>();
    for (int row = 0; row < sampled.length; row++) {
      if (sampled[row] > 0) {
        result.merge(recipe.inputs().get(row).key(), sampled[row], Math::addExact);
      }
    }
    return Map.copyOf(result);
  }

  public static long[] sampledInputRows(RecipeCandidate<?> recipe, long seed, int instances) {
    var sampling = beginInputSampling(recipe, seed, instances);
    while (!sampling.complete()) {
      sampling.advance(Integer.MAX_VALUE);
    }
    return sampling.amounts();
  }

  public static InputSampling beginInputSampling(RecipeCandidate<?> recipe, long seed, int instances) {
    return new InputSampling(recipe, instances, true, index -> inputChanceSeedForInstance(seed, index));
  }

  /**
   * Each step samples at most one row and retains the generator within an
   * instance.
   */
  public static final class InputSampling {
    private final RecipeCandidate<?> recipe;
    private final int instances;
    private final boolean aggregate;
    private final IntToLongFunction instanceSeed;
    private final long[] amounts;
    private int instance;
    private int row;
    private RandomSource random;

    private InputSampling(RecipeCandidate<?> recipe, int instances, boolean aggregate, IntToLongFunction instanceSeed) {
      if (instances < 1) {
        throw new IllegalArgumentException("Invalid input chance instances");
      }
      this.recipe = recipe;
      this.instances = instances;
      this.aggregate = aggregate;
      this.instanceSeed = instanceSeed;
      amounts = new long[recipe.inputs().size()];
    }

    public boolean complete() {
      return instance == instances || amounts.length == 0;
    }

    public long[] amounts() {
      if (!complete()) {
        throw new IllegalStateException("Input sampling is in progress");
      }
      return amounts;
    }

    public int advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative input sampling budget");
      }
      int used = 0;
      while (!complete() && used < budget) {
        if (row == 0) {
          random = RandomSource.create(instanceSeed.applyAsLong(instance));
        }
        var input = recipe.inputs().get(row);
        boolean payable = !aggregate || instance == 0 || !input.parallelUnaffected();
        if (payable && (input.chance() >= 1.0D || input.chance() > 0.0D && random.nextDouble() < input.chance())) {
          long quantity = RecipeItemAmounts.sample(input.minimumCount(), input.count(), random);
          amounts[row] = Math.addExact(amounts[row], quantity);
        }
        used++;
        if (++row == amounts.length) {
          row = 0;
          instance++;
        }
      }
      return used;
    }
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

  public UpgradeRecipeCheckHandlers.Result advanceFactoryChecks(UpgradeBusOwner factory, String threadId, int budget) {
    source.requireServerThread();
    if (source.owner() != factory
        || !source.dependenciesCurrent()
        || !catalog.isCurrent()
        || !inputPreparationCurrent()
        || !modifierContextCurrent(factory, threadId)) {
      return new UpgradeRecipeCheckHandlers.Result(UpgradeRecipeCheckHandlers.Status.STALE, 0);
    }
    if (factoryScope == null) {
      factoryScope = UpgradeRecipeCheckHandlers.capture(factory, threadId);
    }
    if (!factoryScope.matches(factory, threadId) || !factoryScope.current()) {
      return new UpgradeRecipeCheckHandlers.Result(UpgradeRecipeCheckHandlers.Status.STALE, 0);
    }
    if (factoryChecks == null) {
      factoryChecks = factoryScope.begin(this);
    }
    var result = factoryChecks.advance(budget);
    if (result.status() == UpgradeRecipeCheckHandlers.Status.ALLOWED) {
      if (postChecks == null) {
        postChecks = RecipePreparationHandlers.begin(
            source.owner(),
            ResourceLocation.parse(recipe.id()),
            threadId,
            RecipePreparationEvents.Type.POST_CHECK,
            Math.min(preparationLimit, RecipePreparationHandlers.parallelLimit(source, threadId)),
            null);
      }
      int used = result.workUsed() + postChecks.advance(budget - result.workUsed());
      preparationLimit = Math.min(preparationLimit, postChecks.parallelism());
      var status = UpgradeRecipeCheckHandlers.Status.PENDING;
      if (!postChecks.current()) {
        status = UpgradeRecipeCheckHandlers.Status.STALE;
      } else if (postChecks.complete()) {
        status = postChecks
            .rejected() ? UpgradeRecipeCheckHandlers.Status.REJECTED : UpgradeRecipeCheckHandlers.Status.ALLOWED;
      }
      result = new UpgradeRecipeCheckHandlers.Result(status, used);
    }

    return source.dependenciesCurrent()
        && catalog.isCurrent()
        && preparationCurrent() ? result : new UpgradeRecipeCheckHandlers.Result(
            UpgradeRecipeCheckHandlers.Status.STALE,
            result.workUsed());
  }

  public boolean factoryAdmissionCurrent(UpgradeBusOwner factory, String threadId) {
    return factoryChecksCurrent(factory, threadId) && source.dependenciesCurrent();
  }

  boolean factoryChecksCurrent(UpgradeBusOwner factory, String threadId) {
    source.requireServerThread();

    return source.owner() == factory
        && factoryChecks != null
        && factoryChecks.admitted(factory, threadId)
        && postChecks != null
        && postChecks.current()
        && postChecks.complete()
        && !postChecks.rejected()
        && catalog.isCurrent()
        && inputPreparationCurrent()
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

  public UpgradeStartCosts.Preparation prepareFactoryStartup(UpgradeBusOwner factory, String threadId, int instances) {
    source.requireServerThread();
    if (source.owner() != factory) {
      throw new IllegalArgumentException("Startup plan belongs to a different controller");
    }
    var definition = MachineRecipeService.definitionForStart(factory, this).orElse(null);
    if (definition == null || instances < 1 || instances > 1 && !definition.parallelized()) {
      throw new IllegalArgumentException("Recipe does not allow the requested parallel instances");
    }
    if (factoryStartup == null || startupInstances != instances) {
      factoryStartup = UpgradeStartCosts.prepare(factory, threadId, ResourceLocation.parse(recipe.id()), instances);
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

  record BatchInputs(boolean complete, RecipeStartPlan plan, int workUsed) {
  }

  BatchInputs scaled(int instances, int budget) {
    if (budget < 0) {
      throw new IllegalArgumentException("Negative input preparation budget");
    }
    if (instances == 1) {
      return new BatchInputs(true, this, 0);
    }
    return advanceBatch(instances, true, null, budget);
  }

  BatchInputs independentInputs(int instances, int budget) {
    return advanceBatch(instances, false, null, budget);
  }

  static BatchInputs combineInputs(List<RecipeStartPlan> plans, int budget) {
    if (plans == null || plans.isEmpty()) {
      throw new IllegalArgumentException("No parallel recipe plans");
    }
    var snapshot = List.copyOf(plans);
    return snapshot.getFirst().advanceBatch(snapshot.size(), false, snapshot, budget);
  }

  private BatchInputs advanceBatch(int instances, boolean aggregate, List<RecipeStartPlan> plans, int budget) {
    source.requireServerThread();
    if (instances < 1 || budget < 0) {
      throw new IllegalArgumentException("Invalid batch count or budget");
    }
    if (batchInputs == null || !batchInputs.matches(instances, aggregate, plans)) {
      batchInputs = new BatchInputPreparation(instances, aggregate, plans);
    }
    return batchInputs.advance(budget);
  }

  private final class BatchInputPreparation {
    private final int count;
    private final boolean aggregate;
    private final List<RecipeStartPlan> members;
    private final InputSampling sampling;
    private RecipeItemInputAllocation.Cursor reservation;
    private RecipeItemInputAllocation.DeductionCursor debits;
    private int validated;
    private boolean complete;
    private RecipeStartPlan result;

    BatchInputPreparation(int count, boolean aggregate, List<RecipeStartPlan> members) {
      this.count = count;
      this.aggregate = aggregate;
      this.members = members;
      validated = members == null ? count : 0;
      sampling = new InputSampling(recipe, count, aggregate, index -> {
        long seed = members == null ? inputChanceSeed : members.get(index).inputChanceSeed;
        return inputChanceSeedForInstance(seed, index);
      });
    }

    boolean matches(int instances, boolean aggregated, List<RecipeStartPlan> plans) {
      return count == instances && aggregate == aggregated && Objects.equals(members, plans);
    }

    BatchInputs advance(int budget) {
      int used = 0;
      while (validated < count && used < budget) {
        var member = members.get(validated++);
        if (member.source.owner() != source.owner()
            || !samePatternScope(member.source, source)
            || member.source.inputGroup() != source.inputGroup()
            || member.catalog != catalog
            || !member.recipe.equals(recipe)
            || member.inputPreparedEpoch != inputPreparedEpoch) {
          throw new IllegalArgumentException("Parallel plans do not share one recipe owner");
        }
        used++;
      }
      if (validated < count || complete) {
        return new BatchInputs(complete, result, used);
      }
      used += sampling.advance(budget - used);
      if (sampling.complete() && used < budget) {
        if (reservation == null) {
          var definition = preparedInputDefinition().orElseGet(() -> catalog.definition(recipe.id()));
          reservation = RecipeItemInputAllocation
              .beginBatch(recipe, definition, source, count, aggregate, inputAllocation);
        }
        used += reservation.advance(budget - used);
        if (reservation.complete()) {
          var allocation = reservation.allocation().orElse(null);
          if (allocation == null) {
            complete = true;
          } else {
            if (debits == null) {
              debits = allocation.beginDeductions(sampling.amounts());
            }
            used += debits.advance(budget - used);
            if (debits.complete()) {
              result = copyInputPreparationTo(
                  new RecipeStartPlan(
                      source,
                      catalog,
                      recipe,
                      debits.deductions(),
                      placements,
                      inputChanceSeed,
                      factoryScope))
                  .withInputAllocation(allocation);
              complete = true;
            }
          }
          if (complete) {
            reservation = null;
            debits = null;
          }
        }
      }
      return new BatchInputs(complete, result, used);
    }
  }

  private RecipeStartPlan copyInputPreparationTo(RecipeStartPlan target) {
    target.inputPreparedDefinition = inputPreparedDefinition;
    target.originalCandidate = originalCandidate;
    target.inputPreparedEpoch = inputPreparedEpoch;
    target.preparationLimit = preparationLimit;
    target.postChecks = postChecks;
    return target;
  }

  static boolean samePatternScope(RecipeExecutionTicket left, RecipeExecutionTicket right) {
    return left.patternSource() == null ? right.patternSource() == null : left.patternSource()
        .sameScope(right.patternSource());
  }

  public int prepareParallel(MachineRecipe definition, int limit, int budget) {
    return prepareParallel(definition, limit, budget, true);
  }

  /**
   * Pure admission for comparing unresolved alternatives; output callbacks run
   * after selection.
   */
  public int prepareIngredientParallel(MachineRecipe definition, int limit, int budget) {
    return prepareParallel(definition, limit, budget, false);
  }

  private int prepareParallel(MachineRecipe definition, int limit, int budget, boolean prepareOutputCallbacks) {
    source.requireServerThread();
    boolean aggregate = source.owner() instanceof MachineControllerBlockEntity;
    var upgrades = source.ordinaryUpgrades();
    if (!aggregate) {
      if (outputPreparationThread == null) {
        throw new UpgradeRecipeCheckHandlers.StaleException();
      }
      var thread = ((FactoryControllerBlockEntity) source.owner()).scheduler().channel(outputPreparationThread);
      if (thread == null) {
        throw new UpgradeRecipeCheckHandlers.StaleException();
      }
      upgrades = thread.upgradeSnapshot();
    }
    limit = definition.parallelized() ? limit : Math.min(limit, 1);
    limit = Math.min(limit, preparationLimit);
    limit = PatternProductionService.parallelLimit(source, definition, limit);
    boolean upgradesChanged = !upgrades.equals(parallelUpgrades);
    if (parallelAdmission == null || parallelRequest != limit || upgradesChanged) {
      if (outputPreparation != null && (upgradesChanged || !outputPreparation.admittedWithin(limit))) {
        outputPreparation = null;
      }
      parallelAdmission = new ParallelRecipeAdmission(this, definition, limit, aggregate, upgrades);
      parallelRequest = limit;
      parallelUpgrades = upgrades;
    }

    int admitted = parallelAdmission.advance(budget);
    parallelBudgetRemaining = parallelAdmission.remainingBudget();
    if (admitted <= 0 || !prepareOutputCallbacks || !RecipeItemPreparation.transformsOutputs(definition)) {
      return admitted;
    }
    if (outputPreparation == null) {
      outputPreparation = new PreparedRecipeOutputs(this, definition, outputPreparationThread, admitted);
    }
    int result = outputPreparation.advance(parallelBudgetRemaining);
    parallelBudgetRemaining = outputPreparation.remainingBudget();
    return result;
  }

  public int parallelBudgetRemaining() {
    return parallelBudgetRemaining;
  }

  List<FrozenRecipeRun> preparedOutputRuns(int instances) {
    return outputPreparation == null ? List.of() : outputPreparation.runs(instances);
  }

  boolean hasPreparedOutputRuns(int instances) {
    return outputPreparation != null && outputPreparation.current(instances);
  }

  boolean preparedOutputsCurrent(int instances) {
    return outputPreparation == null || outputPreparation.current(instances);
  }

  public boolean modifiedOutputsRejected() {
    return outputPreparation != null && outputPreparation.rejected();
  }

  RecipeResourcePlanning.Progress prepareStartOutputs(MachineRecipe definition, RecipeExecutionTicket ticket, int instances, int budget, boolean factoryBatch) {
    source.requireServerThread();
    if (ticket.owner() != source.owner() || instances < 1) {
      throw new IllegalArgumentException("Invalid start output admission");
    }
    if (startOutputAdmission == null || !startOutputAdmission.matches(definition, instances, factoryBatch)) {
      startOutputAdmission = new RecipeStartOutputAdmission(this, definition, ticket, instances, factoryBatch);
    }

    return startOutputAdmission.advance(budget);
  }

  boolean startOutputsCurrent(MachineRecipe definition, int instances, boolean factoryBatch) {
    return startOutputAdmission != null
        && startOutputAdmission.matches(definition, instances, factoryBatch)
        && startOutputAdmission.current();
  }

  RecipeResourceExecutionRegistry.PaidStartProgress preparePaidResources(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, int budget) {
    var result = RecipeResourceExecutionRegistry.preparePaidStart(ticket, runs, paidStartPlanning, budget);
    paidStartPlanning = result.status() == RecipeResourcePlanning.Status.PENDING ? result.continuation() : null;
    return result;
  }

  public int maxRepeatableInputs() {
    return maxRepeatableInputs(Integer.MAX_VALUE);
  }

  public int maxRepeatableInputs(int limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("Negative input admission limit");
    }
    if (limit == 0) {
      return 0;
    }
    boolean aggregate = source.owner() instanceof MachineControllerBlockEntity;
    if (inputCapacity < 0) {
      inputCapacity = ordinaryInputLimit(aggregate);
      searchInputCounts = null;
    }
    int upper = Math.min(limit, inputCapacity);
    if (upper <= provenPaymentLimit) {
      return upper;
    }
    var definition = preparedInputDefinition().orElseGet(() -> catalog.definition(recipe.id()));
    provenPaymentLimit = RecipeItemPayments.limit(source, definition, upper, aggregate);
    if (provenPaymentLimit < upper) {
      inputCapacity = provenPaymentLimit;
    }
    return provenPaymentLimit;
  }

  private int ordinaryInputLimit(boolean aggregate) {
    var required = new HashMap<ItemResourceKey, Long>();
    var fixed = new HashMap<ItemResourceKey, Long>();
    var aliases = new HashSet<ItemResourceKey>();
    for (var input : recipe.inputs()) {
      (aggregate && input.parallelUnaffected() ? fixed : required).merge(input.key(), input.count(), Math::addExact);
      if (recipe.inputs().size() > 1 && input.key().ignoresComponents()) {
        aliases.add(input.key());
      }
    }
    if (!aliases.isEmpty()) {
      // The whole item pool must cover its exact rows as well as the item-only row.
      try {
        for (var input : recipe.inputs()) {
          if (!input.key().ignoresComponents()) {
            var alias = input.key().ignoringComponents();
            if (aliases.contains(alias)) {
              (aggregate && input.parallelUnaffected() ? fixed : required).merge(alias, input.count(), Math::addExact);
            }
          }
        }
      } catch (ArithmeticException exceedsPool) {
        return 0;
      }
    }
    var available = searchInputCounts == null ? countInputSlots(aliases) : searchInputCounts;
    long result = source.patternSource() == null ? Integer.MAX_VALUE : source.patternSource().startLimit();
    for (var entry : fixed.entrySet()) {
      if (available.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
        return 0;
      }
    }
    for (var entry : required.entrySet()) {
      result = Math.min(
          result,
          Math.max(0, available.getOrDefault(entry.getKey(), 0L) - fixed.getOrDefault(entry.getKey(), 0L)) / entry
              .getValue());
    }

    return (int) result;
  }

  private Map<ItemResourceKey, Long> countInputSlots(Set<ItemResourceKey> aliases) {
    var available = new HashMap<ItemResourceKey, Long>();
    for (var port : source.ports()) {
      if (port.kind() == PortKind.ITEM_INPUT) {
        for (int slot = 0; slot < port.itemSlotCount(); slot++) {
          var key = port.itemKey(slot);
          if (key != null) {
            available.merge(inputKey(recipe, key), port.itemAmount(slot), Math::addExact);
            if (!aliases.isEmpty()) {
              var alias = key.ignoringComponents();
              if (aliases.contains(alias)) {
                available.merge(alias, port.itemAmount(slot), Math::addExact);
              }
            }
          }
        }
      }
    }
    return available;
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
    source.requireServerThread();
    if (outputCapacity < 0) {
      outputCapacity = calculateOutputCapacity();
    }
    return outputCapacity;
  }

  private int calculateOutputCapacity() {
    var definition = preparedInputDefinition().orElseGet(() -> catalog.definition(recipe.id()));
    if (RecipeItemPreparation.transformsOutputs(definition)) {
      return Integer.MAX_VALUE;
    }
    var required = new HashMap<ItemResourceKey, Long>();
    var fixed = new HashMap<ItemResourceKey, Long>();
    boolean aggregate = source.owner() instanceof MachineControllerBlockEntity;
    for (var output : recipe.outputs()) {
      if (output.ignoreOutputCheck()) {
        continue;
      }
      (aggregate && output.parallelUnaffected() ? fixed : required).merge(output.key(), output.count(), Math::addExact);
    }
    long result = Integer.MAX_VALUE;
    var keys = new HashSet<>(required.keySet());
    keys.addAll(fixed.keySet());
    for (var key : keys) {
      long capacity = 0;
      for (var port : source.ports()) {
        capacity = Math.addExact(capacity, port.remainingItemOutputCapacity(key));
      }
      long base = fixed.getOrDefault(key, 0L);
      if (capacity < base) {
        return 0;
      }
      if (required.containsKey(key)) {
        result = Math.min(result, (capacity - base) / required.get(key));
      }
    }

    return (int) result;
  }

  /**
   * The server owner must supply its currently published catalog; this still
   * performs no transaction.
   */
  public boolean validateForStart(RecipeCatalog currentCatalog) {
    source.requireServerThread();
    return currentCatalog == catalog && checkedTicket().isPresent();
  }

  public boolean validateForStart() {
    return validateForStart(catalog);
  }

  public boolean resourcesCurrent() {
    return source.dependenciesCurrent();
  }

  /**
   * Cheap invalidation while preparing a batch. Full live contents are checked
   * before startup.
   */
  public boolean factoryPlanningCurrent() {
    return catalog.isCurrent()
        && inputPreparationCurrent()
        && preparationCurrent()
        && source.planningRevisionsCurrent();
  }

  Optional<RecipeExecutionTicket> checkedTicket() {
    source.requireServerThread();
    if (!catalog.isCurrent() || !inputPreparationCurrent() || !preparationCurrent()) {
      return Optional.empty();
    }
    var fresh = source.currentForCommit();
    return catalog.isCurrent() && inputPreparationCurrent() && preparationCurrent() ? fresh : Optional.empty();
  }

  private boolean preparationCurrent() {
    return preparationEpoch == RecipePreparationHandlers.epoch() && (postChecks == null || postChecks.current());
  }

  RecipeExecutionTicket source() {
    return source;
  }

  RecipeCatalog catalog() {
    return catalog;
  }

  public AtomicRecipeStarter.Result start() {
    return AtomicRecipeStarter.start(this);
  }

  public AtomicRecipeStarter.Result start(int workBudget) {
    return AtomicRecipeStarter.start(this, workBudget);
  }

  public record Deduction(ItemSlotRef slot, ItemResourceKey key, long count) {
    public Deduction {
      if (slot == null || key == null || key.ignoresComponents() || count <= 0) {
        throw new IllegalArgumentException("Invalid slot deduction");
      }
    }
  }

  public record Placement(ItemSlotRef slot, ItemResourceKey key, int count, int availableCapacity) {
    public Placement {
      if (slot == null || key == null || key.ignoresComponents() || count <= 0 || count > availableCapacity) {
        throw new IllegalArgumentException("Invalid slot placement");
      }
    }

    /** Use the same captured, key-specific free space as the output planner. */
    public static Placement inSlot(ItemSlotRef slot, ResourceSnapshot.OutputSlot<ItemResourceKey> output, ItemResourceKey key, int count) {
      long available = output.canAccept(key) ? output.capacityFor(key) - output.count() : 0;
      return new Placement(slot, key, count, Math.toIntExact(available));
    }
  }
}
