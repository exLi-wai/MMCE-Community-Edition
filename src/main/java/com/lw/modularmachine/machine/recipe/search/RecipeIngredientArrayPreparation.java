package com.lw.modularmachine.machine.recipe.search;

import com.google.gson.JsonElement;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeIngredientArrayResolution;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import com.lw.modularmachine.machine.recipe.RecipeItemInputAllocation;
import com.lw.modularmachine.machine.recipe.RecipeItemPreparation;
import com.lw.modularmachine.machine.recipe.RecipePreparationHandlers;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Main-thread, budgeted alternative selection under one recipe ID. Stages and
 * child plans retain preparation progress; the caller must revalidate the
 * selected declaration before paying for it.
 */
@ApiStatus.Internal
final class RecipeIngredientArrayPreparation {
  private enum Stage {
    BRANCH,
    INPUT_MODIFIERS,
    ITEM_INPUTS,
    RESOURCE_INPUTS,
    RESOURCE_ADMISSION,
    RESOURCE_OUTPUTS,
    INPUT_CHECK,
    PARALLEL,
    PARALLEL_INPUT_CHECK,
    FINISHED
  }

  private final RecipeExecutionTicket ticket;
  private final MachineRecipe source;
  private final RecipeCandidate<ItemResourceKey> original;
  private final ResourceSnapshot<ItemResourceKey> snapshot;
  private final List<RecipeSearchResource.Captured> resources;
  private final String thread;
  private final RecipeCatalog catalog;
  private final int preparationLimit;
  private final int[] choices;
  private final RecipeIngredientItemExpansion expansion;
  private final long epoch = RecipeItemCallbackService.epoch();
  private Stage stage = Stage.BRANCH;
  private MachineRecipe definition;
  private RecipeExecutionTicket branchTicket;
  private RecipeItemCallbackService.InputPreparation modifiers;
  private RecipePlanner.Session<ItemResourceKey> planner;
  private RecipeResourcePlanning resourceAdmission;
  private RecipeInputPlanning resourceInputs;
  private int resourceCursor;
  private RecipeResourcePlanning output;
  private RecipeStartPlan parallel;
  private RecipeItemInputAllocation.Cursor inputAllocation;
  private int parallelCount;
  private MachineRecipe best;
  private MachineRecipe waiting;
  private boolean waitingForInputs;
  private int bestCount;
  private int bestParallelLimit = Integer.MAX_VALUE;
  private int workUsed;

  RecipeIngredientArrayPreparation(RecipeExecutionTicket ticket, MachineRecipe source, RecipeCandidate<ItemResourceKey> original, ResourceSnapshot<ItemResourceKey> snapshot, List<RecipeSearchResource.Captured> resources, String thread, RecipeCatalog catalog, int preparationLimit) {
    this.ticket = ticket;
    this.source = source;
    this.original = original;
    this.snapshot = snapshot;
    this.resources = resources;
    this.thread = thread;
    this.catalog = catalog;
    this.preparationLimit = preparationLimit;
    choices = new int[source.ingredientArrays().inputs().size()];
    expansion = new RecipeIngredientItemExpansion(ticket, source);
  }

  boolean finished() {
    return stage == Stage.FINISHED;
  }

  Optional<MachineRecipe> prepared() {
    return finished() ? Optional.ofNullable(best == null ? waiting : best) : Optional.empty();
  }

  int preparedParallelLimit() {
    return bestParallelLimit;
  }

  long epoch() {
    return epoch;
  }

  int workUsed() {
    return workUsed;
  }

  void advance(int budget) {
    ticket.requireServerThread();
    workUsed = 0;
    if (budget < 0) {
      throw new IllegalArgumentException("Negative ingredient array preparation budget");
    }
    if (!RecipeItemCallbackService.isCurrent(epoch) || !ticket.searchContextCurrent() || !catalog.isCurrent()) {
      throw new UpgradeRecipeCheckHandlers.StaleException();
    }
    if (!expansion.complete()) {
      workUsed += expansion.advance(budget);
      if (!expansion.complete()) {
        return;
      }
    }
    var registries = ticket.level().registryAccess();
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    while (!finished() && workUsed < budget) {
      if (!advanceCurrentBranch(budget, registries, ops)) {
        return;
      }
    }
  }

  /**
   * False yields the current branch. Each stage retains its own work charges and
   * yield points; a completed step may still advance its successor with zero
   * remaining work before returning to the caller's branch budget gate.
   */
  private boolean advanceCurrentBranch(int budget, HolderLookup.Provider registries, DynamicOps<JsonElement> ops) {
    // Reservations only read the captured ticket. Revalidate once before the
    // checker runs.
    if (branchTicket != null && inputAllocation == null && !branchTicket.dependenciesCurrent()) {
      workUsed++;
      advanceBranch();
      return true;
    }
    do {
      boolean canContinue = switch (stage) {
        case BRANCH -> beginBranch(registries, ops);
        case INPUT_MODIFIERS -> advanceInputModifiers(budget, registries);
        case ITEM_INPUTS -> advanceItemInputs(budget);
        case RESOURCE_INPUTS -> advanceResourceInputs(budget);
        case RESOURCE_ADMISSION -> advanceResourceAdmission(budget);
        case RESOURCE_OUTPUTS -> advanceResourceOutputs(budget);
        case INPUT_CHECK -> checkSingleInput(budget, registries);
        case PARALLEL -> advanceParallel(budget, registries);
        case PARALLEL_INPUT_CHECK -> checkParallelInputs(budget, registries);
        case FINISHED -> false;
      };
      if (!canContinue) {
        return false;
      }
    } while (stage != Stage.BRANCH && stage != Stage.FINISHED);

    return true;
  }

  private boolean beginBranch(HolderLookup.Provider registries, DynamicOps<JsonElement> ops) {
    workUsed++;
    try {
      beginInputModifiers(registries, ops);
      stage = Stage.INPUT_MODIFIERS;
    } catch (UpgradeRecipeCheckHandlers.StaleException stale) {
      throw stale;
    } catch (RuntimeException invalid) {
      advanceBranch();
    }

    return true;
  }

  private boolean advanceInputModifiers(int budget, HolderLookup.Provider registries) {
    modifiers.advance(budget - workUsed);
    workUsed += modifiers.workUsed();
    if (!modifiers.rejected() && modifiers.prepared().isEmpty()) {
      return false;
    }
    if (modifiers.rejected() || !prepareItemInputs(registries)) {
      advanceBranch();
    } else {
      stage = Stage.ITEM_INPUTS;
    }

    return true;
  }

  private boolean advanceItemInputs(int budget) {
    var result = planner.advance(budget - workUsed);
    workUsed += result.workUsed();
    if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return false;
    }
    if (result.status() == RecipePlanner.Status.NO_MATCH) {
      advanceBranch();
    } else {
      planner = null;
      resourceInputs = new RecipeInputPlanning(original.id(), definition, resources);
      stage = Stage.RESOURCE_INPUTS;
    }

    return true;
  }

  private boolean advanceResourceInputs(int budget) {
    var result = resourceInputs.advance(budget - workUsed);
    workUsed += result.workUsed();
    if (result.status() == RecipeResourcePlanning.Status.PENDING) {
      return false;
    }
    if (result.status() == RecipeResourcePlanning.Status.REJECTED) {
      advanceBranch();
    } else if (resourceInputs.waiting()) {
      waitingForInputs = true;
      stage = Stage.RESOURCE_OUTPUTS;
    } else {
      resourceAdmission = RecipeResourceExecutionRegistry.prepareAdmission(branchTicket, definition.resourceData(), 1);
      stage = Stage.RESOURCE_ADMISSION;
    }
    return true;
  }

  private boolean advanceResourceAdmission(int budget) {
    var result = resourceAdmission.advance(budget - workUsed);
    workUsed += result.workUsed();
    if (result.status() == RecipeResourcePlanning.Status.PENDING) {
      return false;
    }
    if (result.status() == RecipeResourcePlanning.Status.REJECTED) {
      advanceBranch();
    } else {
      resourceAdmission = null;
      stage = Stage.RESOURCE_OUTPUTS;
    }

    return true;
  }

  private boolean advanceResourceOutputs(int budget) {
    while (resourceCursor < resources.size() && workUsed < budget) {
      if (output == null) {
        workUsed++;
        output = resources.get(resourceCursor).outputs(original.id(), definition);
      }
      var result = output.advance(budget - workUsed);
      workUsed += result.workUsed();
      if (result.status() == RecipeResourcePlanning.Status.PENDING) {
        return false;
      }
      if (result.status() == RecipeResourcePlanning.Status.REJECTED) {
        advanceBranch();
        return true;
      }
      output = null;
      resourceCursor++;
    }
    if (resourceCursor < resources.size()) {
      return false;
    }
    stage = Stage.INPUT_CHECK;

    return true;
  }

  private boolean checkSingleInput(int budget, HolderLookup.Provider registries) {
    var check = advanceInputCheck(1, budget, registries);
    if (check == null) {
      return false;
    }
    if (check != RecipeItemCallbackService.InputCheck.ALLOWED) {
      advanceBranch();
    } else if (waitingForInputs) {
      if (waiting == null) {
        waiting = definition;
      }
      advanceBranch();
    } else {
      stage = Stage.PARALLEL;
    }

    return true;
  }

  private boolean advanceParallel(int budget, HolderLookup.Provider registries) {
    if (parallel == null) {
      prepareParallel(registries);
    }
    int limit = parallelLimit();
    int count = parallel.prepareIngredientParallel(definition, limit, budget - workUsed);
    workUsed = budget - parallel.parallelBudgetRemaining();
    if (count < 0) {
      return false;
    }
    if (count > 1) {
      parallelCount = count;
      stage = Stage.PARALLEL_INPUT_CHECK;
    } else {
      finishBranch(count, Integer.MAX_VALUE);
    }
    return true;
  }

  private boolean checkParallelInputs(int budget, HolderLookup.Provider registries) {
    var check = advanceInputCheck(parallelCount, budget, registries);
    if (check == null) {
      return false;
    }
    if (check == RecipeItemCallbackService.InputCheck.ALLOWED) {
      finishBranch(parallelCount, Integer.MAX_VALUE);
    } else if (check == RecipeItemCallbackService.InputCheck.REJECTED) {
      // The single run passed this branch. A checker can depend on the count,
      // so intermediate batch sizes cannot be assumed to pass.
      finishBranch(1, 1);
    } else {
      advanceBranch();
    }
    return true;
  }

  private void finishBranch(int count, int checkerLimit) {
    if (count > bestCount) {
      bestCount = count;
      best = definition;
      bestParallelLimit = checkerLimit;
    }
    if (bestCount >= parallelLimit()) {
      stage = Stage.FINISHED;
    } else {
      advanceBranch();
    }
  }

  private int parallelLimit() {
    int limit = Math.min(preparationLimit, RecipePreparationHandlers.parallelLimit(ticket, thread));
    if (ticket.owner() instanceof MachineControllerBlockEntity ordinary) {
      limit = Math.min(limit, UpgradeStartCosts.maxRuns(ordinary));
    }

    return limit;
  }

  private void beginInputModifiers(HolderLookup.Provider registries, DynamicOps<JsonElement> ops) {
    var selectedChoices = expansion.selected(choices);
    var sourceIndices = selectedChoices.stream().mapToInt(RecipeIngredientArrayResolution.Selection::choice).toArray();
    var rules = RecipeIngredientArrayResolution.selectedRules(source.ingredientArrays(), sourceIndices);
    var contextual = source;
    if (!rules.isEmpty()) {
      contextual = MachineRecipeService
          .resolveIngredientSource(ticket.owner(), catalog, ResourceLocation.parse(original.id()), rules)
          .orElseThrow(UpgradeRecipeCheckHandlers.StaleException::new);
    }
    var declaration = RecipeIngredientArrayResolution.resolve(contextual, selectedChoices, registries);
    var selected = MachineRecipeJson.compileResolved(declaration, ops);
    modifiers = RecipeItemCallbackService.beginInputs(selected, () -> declaration, callbackContext(1), registries);
  }

  private boolean prepareItemInputs(HolderLookup.Provider registries) {
    definition = modifiers.prepared().orElseThrow().definition();
    modifiers = null;
    if (!PatternProductionService.matches(ticket, definition, thread)
        || RecipeResourceRequirements.missing(ticket, definition).isPresent()
        || !RecipeSearch.resourcesFeasible(original.id(), definition, resources)) {
      return false;
    }
    var candidate = RecipeItemPreparation.inputCandidate(
        RecipeItemCallbackService.compilePrepared(definition, ResourceLocation.parse(original.id()), registries),
        definition);
    branchTicket = catalog.ticketForRecipe(ticket, candidate, definition);
    if (!branchTicket.dependenciesCurrent()) {
      return false;
    }
    planner = RecipePlanner.prepareEntries(snapshot, List.of(CompiledRecipeSet.compileCandidate(candidate)));

    return true;
  }

  private RecipeItemCallbackService.InputCheck advanceInputCheck(int instances, int budget, HolderLookup.Provider registries) {
    if (workUsed >= budget) {
      return null;
    }
    if (inputAllocation == null) {
      inputAllocation = RecipeItemInputAllocation
          .begin(definition, branchTicket, instances, ticket.owner() instanceof MachineControllerBlockEntity);
    }
    workUsed += inputAllocation.advance(budget - workUsed);
    if (!inputAllocation.complete() || workUsed >= budget) {
      return null;
    }
    workUsed++;
    if (!branchTicket.dependenciesCurrent()) {
      inputAllocation = null;
      return RecipeItemCallbackService.InputCheck.STALE;
    }
    var slices = inputAllocation.allocation().flatMap(
        allocation -> RecipeIngredientArrayResolution.checkerSlices(definition, branchTicket, instances, allocation));
    inputAllocation = null;
    if (slices.isEmpty()) {
      return RecipeItemCallbackService.InputCheck.REJECTED;
    }
    var check = RecipeItemCallbackService
        .checkInputs(definition.scriptOptions(), callbackContext(instances), slices.orElseThrow(), registries, epoch);

    return switch (check) {
      case ALLOWED, REJECTED -> check;
      case STALE -> throw new UpgradeRecipeCheckHandlers.StaleException();
      case ERROR -> throw new UpgradeRecipeCheckHandlers.FailedException();
    };
  }

  private void prepareParallel(HolderLookup.Provider registries) {
    var exact = RecipeItemCallbackService
        .compilePrepared(definition, ResourceLocation.parse(original.id()), registries);
    parallel = RecipeStartPlan.prepared(
        branchTicket,
        catalog,
        exact,
        List.of(),
        List.of(),
        RecipeStartPlan.newInputChanceSeed(),
        null,
        snapshot).withOutputPreparationContext(thread).withPreparedInputs(definition, original, epoch);
  }

  private RecipeItemCallbacks.Context callbackContext(int instances) {
    var recipeId = ResourceLocation.parse(original.id());
    Optional<String> callbackThread = ticket.owner() instanceof FactoryControllerBlockEntity ? Optional
        .of(thread) : Optional.empty();

    return RecipeItemPreparation.context(ticket, recipeId, callbackThread, instances);
  }

  private void advanceBranch() {
    stage = Stage.BRANCH;
    definition = null;
    branchTicket = null;
    modifiers = null;
    planner = null;
    resourceAdmission = null;
    output = null;
    resourceCursor = 0;
    resourceInputs = null;
    waitingForInputs = false;
    parallel = null;
    inputAllocation = null;
    for (int group = choices.length - 1; group >= 0; group--) {
      var declaration = source.ingredientArrays().inputs().get(group);
      if (choices[group] == -1) {
        choices[group] = 0;
        continue;
      }
      if (++choices[group] < expansion.alternativeCount(group)) {
        return;
      }
      if (declaration.optional()) {
        choices[group] = -1;
        return;
      }
      choices[group] = 0;
    }
    stage = Stage.FINISHED;
  }
}
