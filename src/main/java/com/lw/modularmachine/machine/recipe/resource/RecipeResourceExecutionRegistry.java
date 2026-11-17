package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.RecipeTriggeredResources;
import com.lw.modularmachine.machine.recipe.TriggeredItemExecution;
import com.lw.modularmachine.machine.recipe.PatternInputTransaction;
import com.lw.modularmachine.machine.recipe.RecipeCommitGuard;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.chemical.ChemicalResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.fluid.FluidTickResourceExecution;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningSequence;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal typed execution directory. Existing item/fluid/energy plans retain
 * their dedicated engines; additional resources pass through the same three
 * preparation stages and participate in the same final transaction.
 */
@ApiStatus.Internal
public final class RecipeResourceExecutionRegistry {
  private static final Map<RecipeResourceType<?>, Boolean> ENGINE_OWNED = new IdentityHashMap<>();
  private static final List<Binding<?>> BINDINGS = new ArrayList<>();
  private static List<Binding<?>> installed;

  static {
    ENGINE_OWNED.put(RecipeResourceDefinitions.ITEM, Boolean.TRUE);
    ENGINE_OWNED.put(RecipeResourceDefinitions.FLUID, Boolean.TRUE);
    ENGINE_OWNED.put(RecipeResourceDefinitions.ENERGY, Boolean.TRUE);
    register(
        RecipeResourceDefinitions.CHEMICAL,
        ChemicalResourceExecution.admission(),
        ChemicalResourceExecution.start(),
        ChemicalResourceExecution.tick(),
        ChemicalResourceExecution.finish(),
        ChemicalResourceExecution.freeze(),
        ChemicalResourceExecution.validation());
    register(
        RecipeResourceDefinitions.FLUID,
        FluidTickResourceExecution.admission(),
        FluidTickResourceExecution.start(),
        FluidTickResourceExecution.tick(),
        FluidTickResourceExecution.finish(),
        FluidTickResourceExecution.freeze(),
        FluidTickResourceExecution.validation());
  }

  private RecipeResourceExecutionRegistry() {
  }

  private record Binding<D>(
      RecipeResourceType<D> type,
      RecipeResourceExecution.Admission<D> admission,
      RecipeResourceExecution.Start<D> start,
      RecipeResourceExecution.Tick<D> tick,
      RecipeResourceExecution.Finish<D> finish,
      RecipeResourceExecution.Freeze<D> freeze,
      RecipeResourceExecution.Validation<D> validation) {
  }

  public static synchronized <D> void register(RecipeResourceType<D> type, RecipeResourceExecution.Admission<D> admission, RecipeResourceExecution.Start<D> start, RecipeResourceExecution.Tick<D> tick, RecipeResourceExecution.Finish<D> finish, RecipeResourceExecution.Freeze<D> freeze, RecipeResourceExecution.Validation<D> validation) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(tick, "tick");
    Objects.requireNonNull(finish, "finish");
    Objects.requireNonNull(freeze, "freeze");
    Objects.requireNonNull(validation, "validation");
    RecipeResourceDefinitions.requireRegistered(type);
    if (installed != null
        || ENGINE_OWNED.containsKey(type) && type != RecipeResourceDefinitions.FLUID
        || BINDINGS.stream().anyMatch(binding -> binding.type().id().equals(type.id()))) {
      throw new IllegalStateException("Duplicate or late resource execution registration: " + type.id());
    }
    BINDINGS.add(new Binding<>(type, admission, start, tick, finish, freeze, validation));
  }

  private static synchronized List<Binding<?>> snapshot() {
    if (installed == null) {
      BINDINGS.forEach(binding -> RecipeResourceDefinitions.requireRegistered(binding.type()));
      installed = List.copyOf(BINDINGS);
    }

    return installed;
  }

  private static void requireSupported(RecipeResources resources, List<Binding<?>> bindings) {
    for (var entry : resources.entries()) {
      requireSupported(entry, bindings);
    }
  }

  private static <D> void requireSupported(RecipeResources.Entry<D> entry, List<Binding<?>> bindings) {
    RecipeResourceDefinitions.requireRegistered(entry.type());
    if (!entry.type().isEmpty(entry.data())
        && !ENGINE_OWNED.containsKey(entry.type())
        && bindings.stream().noneMatch(binding -> binding.type() == entry.type())) {
      throw new IllegalStateException("Resource has no installed execution strategy: " + entry.type().id());
    }
  }

  /**
   * Checks all strategies before any prospective recipe manager or catalog is
   * installed.
   */
  public static void validateDefinition(ResourceLocation recipeId, RecipeResources resources, HolderLookup.Provider registries) {
    Objects.requireNonNull(registries, "registries");
    try {
      var bindings = snapshot();
      requireSupported(resources, bindings);
      for (var binding : bindings) {
        validateDefinition(binding, resources, registries);
      }
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(recipeId + ": Invalid recipe resource: " + invalid.getMessage(), invalid);
    }
  }

  private static <D> void validateDefinition(Binding<D> binding, RecipeResources resources, HolderLookup.Provider registries) {
    var data = resources.get(binding.type());
    if (!binding.type().isEmpty(data)) {
      binding.validation().validate(data, registries);
    }
  }

  public static Prepared prepareStart(RecipeExecutionTicket ticket, RecipeResources resources, int instances) {
    ticket.requireServerThread();
    var bindings = snapshot();
    requireSupported(resources, bindings);
    var prepared = new ArrayList<PreparedRecipeResource>();
    for (var binding : bindings) {
      if (isEmpty(binding, resources)) {
        continue;
      }
      var value = prepareStart(binding, ticket, resources, instances);
      if (value == null) {
        return null;
      }
      prepared.add(value);
    }

    return new Prepared(prepared, List.of(ticket), !prepared.isEmpty());
  }

  private static <D> PreparedRecipeResource prepareStart(Binding<D> binding, RecipeExecutionTicket ticket, RecipeResources resources, int instances) {
    return binding.start()
        .prepare(List.of(new RecipeResourceExecution.Input<>(ticket, resources.get(binding.type()), instances)));
  }

  /**
   * Frozen run seeds select actual debits only after full shared demand proof.
   */
  public static Prepared preparePaidStart(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs) {
    ticket.requireServerThread();
    if (runs.isEmpty()) {
      throw new IllegalArgumentException("A paid start requires frozen runs");
    }
    var bindings = snapshot();
    var resources = runs.stream().map(run -> run.resourceData(ticket.level().registryAccess())).toList();
    resources.forEach(value -> requireSupported(value, bindings));
    var participants = new ArrayList<PreparedRecipeResource>();
    for (var binding : bindings) {
      if (resources.stream().allMatch(value -> isEmpty(binding, value))) {
        continue;
      }
      var participant = preparePaidStart(binding, ticket, runs, resources);
      if (participant == null) {
        return null;
      }
      participants.add(participant);
    }

    return new Prepared(participants, List.of(ticket), !participants.isEmpty());
  }

  private static <D> PreparedRecipeResource preparePaidStart(Binding<D> binding, RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<RecipeResources> resources) {
    var claims = new ArrayList<RecipeResourceExecution.Input<D>>();
    for (int index = 0; index < runs.size(); index++) {
      var data = resources.get(index).get(binding.type());
      claims.add(new RecipeResourceExecution.Input<>(ticket, data, 1, Optional.of(runs.get(index))));
    }

    return binding.start().prepare(List.copyOf(claims));
  }

  /**
   * Lazy, read-only checks for one binary-search count. No prepared participant
   * escapes this admission and no resource is acquired; commit prepares again.
   */
  public static RecipeResourcePlanning prepareAdmission(RecipeExecutionTicket ticket, RecipeResources resources, int instances) {
    ticket.requireServerThread();
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid admission instances");
    }
    var bindings = snapshot();
    requireSupported(resources, bindings);
    var steps = new ArrayList<Supplier<RecipeResourcePlanning>>();
    for (var binding : bindings) {
      if (!isEmpty(binding, resources)) {
        steps.add(() -> prepareAdmission(binding, ticket, resources, instances));
      }
    }

    return new ResourcePlanningSequence(steps);
  }

  private static <D> RecipeResourcePlanning prepareAdmission(Binding<D> binding, RecipeExecutionTicket ticket, RecipeResources resources, int instances) {
    return new AdmissionPlanning(
        () -> prepareStart(binding, currentPreparationTicket(ticket), resources, instances),
        () -> binding.admission().prepare(
            new RecipeResourceExecution.Input<>(
                currentPreparationTicket(ticket),
                resources.get(binding.type()),
                instances)));
  }

  private static RecipeExecutionTicket currentPreparationTicket(RecipeExecutionTicket ticket) {
    return ticket.refreshIfUnchanged().filter(RecipeCommitGuard::validate)
        .orElseThrow(UpgradeRecipeCheckHandlers.StaleException::new);
  }

  /**
   * Keeps the input check and output-plan construction inside the shared budget.
   */
  static final class AdmissionPlanning implements RecipeResourcePlanning {
    private final Supplier<PreparedRecipeResource> inputs;
    private final Supplier<RecipeResourcePlanning> outputs;
    private boolean inputsChecked;
    private RecipeResourcePlanning output;
    private Status status = Status.PENDING;

    AdmissionPlanning(Supplier<PreparedRecipeResource> inputs, Supplier<RecipeResourcePlanning> outputs) {
      this.inputs = Objects.requireNonNull(inputs, "inputs");
      this.outputs = Objects.requireNonNull(outputs, "outputs");
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    public Progress advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative resource admission budget");
      }
      if (status != Status.PENDING || budget == 0) {
        return new Progress(status, 0);
      }
      int used = 0;
      try {
        if (!inputsChecked) {
          used++;
          var prepared = inputs.get();
          if (prepared == null || !prepared.current()) {
            status = Status.REJECTED;
            return new Progress(status, used);
          }
          inputsChecked = true;
        }
        if (output == null && used < budget) {
          used++;
          output = Objects.requireNonNull(outputs.get(), "resource admission output plan");
          status = output.status();
        }
        if (output != null && status == Status.PENDING && used < budget) {
          var result = output.advance(budget - used);
          if (result.workUsed() > budget - used || result.status() != output.status()) {
            throw new IllegalStateException("Resource admission violated its progress contract");
          }
          used += result.workUsed();
          status = result.status();
        }
      } catch (ArithmeticException overflow) {
        // A failing strategy cannot report partial work; spend the remaining
        // slice conservatively before the binary search tries a smaller count.
        used = budget;
        status = Status.REJECTED;
      }

      return new Progress(status, used);
    }
  }

  private static <D> boolean isEmpty(Binding<D> binding, RecipeResources resources) {
    return binding.type().isEmpty(resources.get(binding.type()));
  }

  /** Runs before the existing engine-owned item/fluid/energy freezing. */
  public static void freezeDefinition(CompoundTag definition, int instances, HolderLookup.Provider registries) {
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid frozen resource instances");
    }
    for (var binding : snapshot()) {
      freezeDefinition(binding, definition, instances, registries);
    }
  }

  private static <D> void freezeDefinition(Binding<D> binding, CompoundTag definition, int instances, HolderLookup.Provider registries) {
    var ops = registries.createSerializationContext(NbtOps.INSTANCE);
    var ownedFields = RecipeResourceDefinitions.fields(binding.type());
    var data = RecipeResourceDefinitions.decodeOwned(binding.type(), ops, definition).getOrThrow();
    if (binding.type().isEmpty(data)) {
      return;
    }
    var frozen = binding.type().dataClass()
        .cast(Objects.requireNonNull(binding.freeze().freeze(data, instances), "frozen resource"));
    var encoded = binding.type().nativeCodec().codec().encodeStart(ops, frozen).getOrThrow();
    if (!(encoded instanceof CompoundTag fields)) {
      throw new IllegalStateException("Resource freeze did not encode native fields: " + binding.type().id());
    }
    for (var field : fields.getAllKeys()) {
      if (!ownedFields.contains(field)) {
        throw new IllegalStateException("Resource freeze wrote another descriptor's field: " + field);
      }
    }
    ownedFields.forEach(definition::remove);
    for (var field : fields.getAllKeys()) {
      definition.put(field, fields.get(field).copy());
    }
  }

  public record TickPreparation(
      RecipeResourceExecution.TickStatus status,
      RecipeResourceType<?> resource,
      Prepared prepared) {
    public TickPreparation {
      if ((status == RecipeResourceExecution.TickStatus.READY) != (prepared != null)) {
        throw new IllegalArgumentException("Invalid combined resource tick preparation");
      }
    }
  }

  public static TickPreparation prepareTick(StructureService.StructureSubject owner, List<FrozenRecipeRun> runs) {
    if (!(owner.structureLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
      throw new IllegalStateException("Resource tick preparation outside server thread");
    }
    var bindings = snapshot();
    var resources = runs.stream()
        .map(run -> RecipeTriggeredResources.effective(run, run.resourceData(owner.structureLevel().registryAccess())))
        .toList();
    resources.forEach(value -> requireSupported(value, bindings));
    var prepared = new ArrayList<PreparedRecipeResource>();
    if (runs.stream().anyMatch(run -> !run.triggeredResources().isEmpty())) {
      var triggeredItems = TriggeredItemExecution.prepare(owner, runs);
      if (triggeredItems.status() != RecipeResourceExecution.TickStatus.READY) {
        return new TickPreparation(triggeredItems.status(), RecipeResourceDefinitions.ITEM, null);
      }
      // Scheduled IO also needs transactional progress before its due position.
      prepared.add(triggeredItems.prepared());
    }
    for (var binding : bindings) {
      var result = prepareTick(binding, owner, runs, resources);
      if (result == null) {
        continue;
      }
      if (result.status() != RecipeResourceExecution.TickStatus.READY) {
        return new TickPreparation(result.status(), binding.type(), null);
      }
      prepared.add(result.prepared());
    }

    return new TickPreparation(
        RecipeResourceExecution.TickStatus.READY,
        null,
        new Prepared(prepared, List.of(), !prepared.isEmpty()));
  }

  public static boolean hasTickResources(FrozenRecipeRun run, HolderLookup.Provider registries) {
    var bindings = snapshot();
    var resources = run.resourceData(registries);
    requireSupported(resources, bindings);

    return !run.triggeredResources().isEmpty()
        || bindings.stream().anyMatch(binding -> tickRequired(binding, resources));
  }

  private static <D> boolean tickRequired(Binding<D> binding, RecipeResources resources) {
    var data = resources.get(binding.type());

    return !binding.type().isEmpty(data) && binding.tick().required(data);
  }

  /**
   * A failure-policy recheck evaluates only the resource that actually failed.
   */
  public static boolean stillUnavailable(StructureService.StructureSubject owner, List<FrozenRecipeRun> runs, RecipeResourceType<?> resource) {
    if (resource == RecipeResourceDefinitions.ITEM) {
      return TriggeredItemExecution.prepare(owner, runs).status() == RecipeResourceExecution.TickStatus.INSUFFICIENT;
    }
    var resources = runs.stream()
        .map(run -> RecipeTriggeredResources.effective(run, run.resourceData(owner.structureLevel().registryAccess())))
        .toList();
    for (var binding : snapshot()) {
      if (binding.type() == resource) {
        var result = prepareTick(binding, owner, runs, resources);

        return result != null && result.status() == RecipeResourceExecution.TickStatus.INSUFFICIENT;
      }
    }

    return false;
  }

  public static boolean stillUnavailable(StructureService.StructureSubject owner, List<FrozenRecipeRun> runs, ResourceType resource) {
    if (resource == ResourceType.ITEM) {
      return stillUnavailable(owner, runs, RecipeResourceDefinitions.ITEM);
    }
    for (var binding : snapshot()) {
      if (binding.type().id().equals(resource.id())) {
        return stillUnavailable(owner, runs, binding.type());
      }
    }

    return false;
  }

  private static <D> RecipeResourceExecution.TickResult prepareTick(Binding<D> binding, StructureService.StructureSubject owner, List<FrozenRecipeRun> runs, List<RecipeResources> resources) {
    var claims = new ArrayList<RecipeResourceExecution.Run<D>>();
    for (int index = 0; index < runs.size(); index++) {
      var data = resources.get(index).get(binding.type());
      if (!binding.type().isEmpty(data) && binding.tick().required(data)) {
        claims.add(new RecipeResourceExecution.Run<>(runs.get(index), data));
      }
    }

    return claims.isEmpty() ? null : Objects
        .requireNonNull(binding.tick().prepare(owner, List.copyOf(claims)), "resource tick result");
  }

  public static OutputPlanning prepareOutputs(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs) {
    ticket.requireServerThread();
    var bindings = snapshot();
    var resources = runs.stream().map(run -> run.resourceData(ticket.level().registryAccess())).toList();
    resources.forEach(value -> requireSupported(value, bindings));
    var plans = new ArrayList<Supplier<RecipeResourceExecution.Output>>();
    for (var binding : bindings) {
      prepareOutputs(binding, ticket, runs, resources).ifPresent(plans::add);
    }

    return new OutputPlanning(ticket, List.copyOf(plans));
  }

  private static <D> Optional<Supplier<RecipeResourceExecution.Output>> prepareOutputs(Binding<D> binding, RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<RecipeResources> resources) {
    var claims = new ArrayList<RecipeResourceExecution.Run<D>>();
    for (int index = 0; index < runs.size(); index++) {
      var data = resources.get(index).get(binding.type());
      if (binding.type().hasOutputs(data)) {
        claims.add(new RecipeResourceExecution.Run<>(runs.get(index), data));
      }
    }

    if (claims.isEmpty()) {
      return Optional.empty();
    }

    var captured = List.copyOf(claims);

    return Optional.of(
        () -> Objects.requireNonNull(
            binding.finish().prepare(currentPreparationTicket(ticket), captured),
            "resource output plan"));
  }

  public static final class Prepared implements PreparedRecipeResource {
    private final List<PreparedRecipeResource> participants;
    private final List<RecipeExecutionTicket> proofs;
    private final boolean work;
    private final RecipeResourceTransaction transaction = new RecipeResourceTransaction();
    private boolean acquired, resolved, committed;

    private Prepared(List<PreparedRecipeResource> participants, List<RecipeExecutionTicket> proofs, boolean work) {
      this.participants = List.copyOf(participants);
      this.proofs = List.copyOf(proofs);
      this.work = work;
      for (var participant : participants) {
        transaction.phase().enlist(participant);
      }
    }

    public boolean hasWork() {
      return work;
    }

    @Override
    public boolean current() {
      return !resolved
          && proofs.stream().allMatch(RecipeExecutionTicket::dependenciesCurrent)
          && participants.stream().allMatch(PreparedRecipeResource::current);
    }

    @Override
    public void addPatterns(PatternInputTransaction patterns) {
      participants.forEach(participant -> participant.addPatterns(patterns));
    }

    @Override
    public void acquire() {
      // Preflight belongs before any participant acquires locks. Never invoke
      // structure predicates or callbacks in the critical acquisition section.
      if (acquired || resolved) {
        throw new IllegalStateException("Stale or acquired resource preparation");
      }
      acquired = true;
      participants.forEach(PreparedRecipeResource::acquire);
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
      resolved = true;
      committed = commit;
    }

    @Override
    public void publish() {
      if (!resolved) {
        throw new IllegalStateException("Unresolved resource preparation");
      }
      if (committed) {
        RecipeResourceTransaction.publishAll(
            participants,
            PreparedRecipeResource::publish,
            error -> ModularMachine.LOGGER.warn("Committed resource strategy notification failed", error));
      }
    }
  }

  public static final class OutputPlanning {
    private final RecipeExecutionTicket ticket;
    private final List<RecipeResourceExecution.Output> plans = new ArrayList<>();
    private final List<Supplier<RecipeResourcePlanning>> steps;
    private ResourcePlanningSequence sequence;

    OutputPlanning(RecipeExecutionTicket ticket, List<Supplier<RecipeResourceExecution.Output>> factories) {
      this.ticket = ticket;
      var pendingSteps = new ArrayList<Supplier<RecipeResourcePlanning>>(factories.size());
      for (var factory : factories) {
        pendingSteps.add(() -> {
          var plan = Objects.requireNonNull(factory.get(), "resource output plan");
          plans.add(plan);
          return plan;
        });
      }
      steps = List.copyOf(pendingSteps);
      sequence = new ResourcePlanningSequence(steps);
    }

    public RecipePlanner.Status status() {
      return switch (sequence.status()) {
        case READY -> RecipePlanner.Status.SELECTED;
        case REJECTED -> RecipePlanner.Status.NO_MATCH;
        case PENDING -> RecipePlanner.Status.BUDGET_EXHAUSTED;
      };
    }

    public record Progress(RecipePlanner.Status status, int workUsed) {
    }

    /**
     * Owners call once per tick with their remaining output budget. A rejected
     * extension may depend on private storage outside the native ticket, so retry
     * its snapshot without discarding completed native item/fluid planning.
     */
    public Progress advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative resource output budget");
      }
      int used = 0;
      if (sequence.status() == RecipeResourcePlanning.Status.REJECTED && budget > 0) {
        // Spend work before rebuilding, at most once per owner attempt. Pending
        // retries keep their continuation even when the remaining budget is zero.
        used++;
        plans.clear();
        sequence = new ResourcePlanningSequence(steps);
      }
      var result = sequence.advance(budget - used);

      return new Progress(status(), used + result.workUsed());
    }

    public Prepared prepared(RecipeExecutionTicket checked) {
      if (status() != RecipePlanner.Status.SELECTED
          || !ticket.dependenciesCurrent()
          || ticket.owner() != checked.owner()
          || ticket.definitionEpoch() != checked.definitionEpoch()
          || ticket.generation() != checked.generation()
          || ticket.structureRevision() != checked.structureRevision()
          || ticket.inputGroup() != checked.inputGroup()
          || !RecipeCommitGuard.validate(checked)) {
        throw new IllegalStateException("Stale or incomplete resource output plan");
      }
      var participants = plans.stream()
          .map(plan -> Objects.requireNonNull(plan.prepared(checked), "prepared resource output")).toList();

      return new Prepared(participants, List.of(checked), !participants.isEmpty());
    }
  }
}
