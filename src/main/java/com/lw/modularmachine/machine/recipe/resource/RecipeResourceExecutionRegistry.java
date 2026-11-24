package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import java.util.BitSet;
import com.lw.modularmachine.machine.recipe.TriggeredItemExecution;
import com.lw.modularmachine.machine.recipe.PatternInputTransaction;
import com.lw.modularmachine.machine.recipe.RecipeCommitGuard;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.chemical.ChemicalResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.environment.EnvironmentRecipeExecution;
import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.machine.recipe.resource.buffered.BufferedRecipeResources;
import com.lw.modularmachine.machine.recipe.resource.pneumatic.CompressedAirRecipeExecution;
import com.lw.modularmachine.machine.recipe.resource.world.WorldPoolResources;
import com.lw.modularmachine.machine.recipe.resource.world.MeteorExecution;
import com.lw.modularmachine.machine.recipe.resource.world.ConstellationExecution;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedRecipeResources;
import com.lw.modularmachine.util.Mods;
import com.lw.modularmachine.machine.recipe.resource.fluid.FluidTickResourceExecution;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements.RequiredPort;
import java.util.Set;
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
import java.util.Collections;

/**
 * Internal typed execution directory. Existing item/fluid/energy plans retain
 * their dedicated engines; additional resources pass through the same three
 * preparation stages and participate in the same final transaction.
 */
@ApiStatus.Internal
public final class RecipeResourceExecutionRegistry {
  private static final Map<RecipeResourceType<?>, Boolean> ENGINE_OWNED = new IdentityHashMap<>();
  private static final List<Binding<?>> BINDINGS = new ArrayList<>();
  private static final int BUILTIN_BINDINGS;
  private static volatile List<Binding<?>> installed;

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
    EnvironmentRecipeExecution.register();
    BufferedRecipeResources.LASER.register(Mods.MEKANISM, LaserRecipeData::new);
    BufferedRecipeResources.EMBER.register(Mods.EMBERS, EmberRecipeData::new);
    BufferedRecipeResources.MANA.register(Mods.BOTANIA, BufferedAmountRecipeData::scaled);
    BufferedRecipeResources.HEAT.register(Mods.MEKANISM, BufferedAmountRecipeData::scaled);
    CompressedAirRecipeExecution.register();
    BufferedRecipeResources.AURA.register(Mods.NATURES_AURA, amount -> new BufferedAmountRecipeData(amount, 0));
    WorldPoolResources.register();
    BufferedRecipeResources.LIFE_ESSENCE.register(Mods.NEO_VITAE, BufferedAmountRecipeData::scaled);
    MeteorExecution.register();
    KeyedRecipeResources.register();
    ConstellationExecution.register();
    BUILTIN_BINDINGS = BINDINGS.size();
  }

  private RecipeResourceExecutionRegistry() {
  }

  private record Binding<D>(
      RecipeResourceType<D> type,
      RecipeResourceExecution.Admission<D> admission,
      RecipeResourceExecution.PlannedStart<D> plannedStart,
      RecipeResourceExecution.Start<D> start,
      RecipeResourceExecution.Tick<D> tick,
      RecipeResourceExecution.Finish<D> finish,
      RecipeResourceExecution.Freeze<D> freeze,
      RecipeResourceExecution.Validation<D> validation) {
  }

  public static synchronized <D> void register(RecipeResourceType<D> type, RecipeResourceExecution.Admission<D> admission, RecipeResourceExecution.Start<D> start, RecipeResourceExecution.Tick<D> tick, RecipeResourceExecution.Finish<D> finish, RecipeResourceExecution.Freeze<D> freeze, RecipeResourceExecution.Validation<D> validation) {
    register(type, admission, null, Objects.requireNonNull(start, "start"), tick, finish, freeze, validation);
  }

  public static synchronized <D> void registerPlannedStart(RecipeResourceType<D> type, RecipeResourceExecution.Admission<D> admission, RecipeResourceExecution.PlannedStart<D> start, RecipeResourceExecution.Tick<D> tick, RecipeResourceExecution.Finish<D> finish, RecipeResourceExecution.Freeze<D> freeze, RecipeResourceExecution.Validation<D> validation) {
    register(type, admission, Objects.requireNonNull(start, "plannedStart"), null, tick, finish, freeze, validation);
  }

  private static <D> void register(RecipeResourceType<D> type, RecipeResourceExecution.Admission<D> admission, RecipeResourceExecution.PlannedStart<D> plannedStart, RecipeResourceExecution.Start<D> start, RecipeResourceExecution.Tick<D> tick, RecipeResourceExecution.Finish<D> finish, RecipeResourceExecution.Freeze<D> freeze, RecipeResourceExecution.Validation<D> validation) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(admission, "admission");
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
    BINDINGS.add(new Binding<>(type, admission, plannedStart, start, tick, finish, freeze, validation));
  }

  private static List<Binding<?>> snapshot() {
    var published = installed;
    return published == null ? install() : published;
  }

  private static synchronized List<Binding<?>> install() {
    if (installed == null) {
      BINDINGS.forEach(binding -> RecipeResourceDefinitions.requireRegistered(binding.type()));
      installed = List.copyOf(BINDINGS);
    }

    return installed;
  }

  /**
   * Indices preserve installed strategy order, including optional resource
   * plugins.
   */
  public static final class TickStrategies {
    private final BitSet indices = new BitSet();
    private final boolean triggered;

    private TickStrategies(RecipeResources resources, TriggeredResourceData triggers) {
      var bindings = snapshot();
      requireSupported(resources, bindings);
      triggered = !triggers.isEmpty();
      for (int i = 0; i < bindings.size(); i++) {
        var binding = bindings.get(i);
        if (tickRequired(binding, resources)
            || binding.type() == RecipeResourceDefinitions.FLUID
                && triggers.rows().stream().anyMatch(row -> row.resource().equals("fluid"))
            || binding.type() == RecipeResourceDefinitions.CHEMICAL
                && triggers.rows().stream().anyMatch(row -> row.resource().equals("chemical"))) {
          indices.set(i);
        }
      }
    }
  }

  public static TickStrategies tickStrategies(RecipeResources resources, TriggeredResourceData triggers) {
    return new TickStrategies(resources, triggers);
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

  private static <D> PreparedRecipeResource prepareStart(Binding<D> binding, RecipeExecutionTicket ticket, RecipeResources resources, int instances) {
    return binding.start()
        .prepare(List.of(new RecipeResourceExecution.Input<>(ticket, resources.get(binding.type()), instances)));
  }

  /**
   * Frozen run seeds select actual debits only after full shared demand proof.
   */
  public static PaidStartProgress preparePaidStart(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, PaidStartPlanning previous, int budget) {
    ticket.requireServerThread();
    if (runs.isEmpty()) {
      throw new IllegalArgumentException("A paid start requires frozen runs");
    }
    var bindings = snapshot();
    var resources = runs.stream().map(run -> run.resourceData(ticket.level().registryAccess())).toList();
    resources.forEach(value -> requireSupported(value, bindings));
    var inputs = new ArrayList<PlannedInput<?>>();
    for (var binding : bindings) {
      if (binding.plannedStart() != null) {
        capturePlannedInput(binding, resources).ifPresent(inputs::add);
      }
    }
    var work = previous != null && previous.inputs.equals(inputs) ? previous : new PaidStartPlanning(ticket, inputs);
    var progress = work.sequence.advance(budget);
    if (progress.status() != RecipeResourcePlanning.Status.READY) {
      return new PaidStartProgress(progress.status(), work, null);
    }
    var participants = new ArrayList<PreparedRecipeResource>();
    for (var binding : bindings) {
      if (binding.plannedStart() != null || resources.stream().allMatch(value -> isEmpty(binding, value))) {
        continue;
      }
      var participant = preparePaidStart(binding, ticket, runs, resources);
      if (participant == null) {
        return new PaidStartProgress(RecipeResourcePlanning.Status.REJECTED, work, null);
      }
      participants.add(participant);
    }
    for (var plan : work.plans) {
      participants.add(Objects.requireNonNull(plan.prepared(ticket), "prepared recipe input"));
    }
    return new PaidStartProgress(
        RecipeResourcePlanning.Status.READY,
        work,
        new Prepared(participants, List.of(ticket), !participants.isEmpty()));
  }

  public record PaidStartProgress(
      RecipeResourcePlanning.Status status,
      PaidStartPlanning continuation,
      Prepared prepared) {
  }

  public static final class PaidStartPlanning {
    private final List<PlannedInput<?>> inputs;
    private final List<RecipeResourceExecution.Preparation> plans = new ArrayList<>();
    private final ResourcePlanningSequence sequence;

    private PaidStartPlanning(RecipeExecutionTicket ticket, List<PlannedInput<?>> inputs) {
      this.inputs = List.copyOf(inputs);
      var steps = new ArrayList<Supplier<RecipeResourcePlanning>>(inputs.size());
      for (var input : inputs) {
        steps.add(() -> {
          var plan = input.prepare(currentPreparationTicket(ticket));
          plans.add(plan);
          return plan;
        });
      }
      sequence = new ResourcePlanningSequence(steps);
    }
  }

  private record PlannedInput<D>(RecipeResourceExecution.PlannedStart<D> strategy, List<D> claims) {
    private RecipeResourceExecution.Preparation prepare(RecipeExecutionTicket ticket) {
      return strategy.prepare(ticket, claims);
    }
  }

  private static <D> Optional<PlannedInput<D>> capturePlannedInput(Binding<D> binding, List<RecipeResources> resources) {
    var type = binding.type();
    var claims = resources.stream().map(value -> value.get(type)).filter(type::hasInputs).toList();
    return claims.isEmpty() ? Optional.empty() : Optional.of(new PlannedInput<>(binding.plannedStart(), claims));
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
    return prepareAdmission(ticket, resources, instances, null);
  }

  public static RecipeResourcePlanning prepareAdmission(RecipeExecutionTicket ticket, RecipeResources resources, int instances, List<RecipeResourceExecution.Output> liveOutputs) {
    return prepareAdmission(ticket, resources, instances, liveOutputs, true);
  }

  public static ResourcePlanningSequence prepareOutputAdmission(RecipeExecutionTicket ticket, RecipeResources resources, int instances, List<RecipeResourceExecution.Output> liveOutputs) {
    return prepareAdmission(ticket, resources, instances, liveOutputs, false);
  }

  private static ResourcePlanningSequence prepareAdmission(RecipeExecutionTicket ticket, RecipeResources resources, int instances, List<RecipeResourceExecution.Output> liveOutputs, boolean checkInputs) {
    ticket.requireServerThread();
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid admission instances");
    }
    var bindings = snapshot();
    requireSupported(resources, bindings);
    var steps = new ArrayList<Supplier<RecipeResourcePlanning>>();
    for (var binding : bindings) {
      if (checkInputs ? !isEmpty(binding, resources) : hasOutputs(binding, resources)) {
        steps.add(() -> prepareAdmission(binding, ticket, resources, instances, liveOutputs, checkInputs));
      }
    }

    return new ResourcePlanningSequence(steps);
  }

  private static <D> RecipeResourcePlanning prepareAdmission(Binding<D> binding, RecipeExecutionTicket ticket, RecipeResources resources, int instances, List<RecipeResourceExecution.Output> liveOutputs, boolean checkInputs) {
    Supplier<RecipeResourcePlanning> outputs = () -> {
      var output = binding.admission().prepare(
          new RecipeResourceExecution.Input<>(
              currentPreparationTicket(ticket),
              resources.get(binding.type()),
              instances));
      if (liveOutputs != null && output instanceof RecipeResourceExecution.Output live) {
        liveOutputs.add(live);
      }
      return output;
    };
    if (!checkInputs) {
      return new AdmissionPlanning(null, null, outputs);
    }
    if (binding.plannedStart() != null) {
      return new AdmissionPlanning(
          null,
          () -> binding.plannedStart().prepare(
              currentPreparationTicket(ticket),
              List.of(binding.freeze().freeze(resources.get(binding.type()), instances))),
          outputs);
    }
    return new AdmissionPlanning(
        () -> prepareStart(binding, currentPreparationTicket(ticket), resources, instances),
        outputs);
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
    private final Supplier<RecipeResourcePlanning> inputPlanner;
    private final Supplier<RecipeResourcePlanning> outputs;
    private boolean inputsChecked;
    private RecipeResourcePlanning inputPlan;
    private RecipeResourcePlanning output;
    private Status status = Status.PENDING;

    AdmissionPlanning(Supplier<PreparedRecipeResource> inputs, Supplier<RecipeResourcePlanning> outputs) {
      this(Objects.requireNonNull(inputs, "inputs"), null, outputs);
    }

    private AdmissionPlanning(Supplier<PreparedRecipeResource> inputs, Supplier<RecipeResourcePlanning> inputPlanner, Supplier<RecipeResourcePlanning> outputs) {
      this.inputs = inputs;
      this.inputPlanner = inputPlanner;
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
        if (!inputsChecked && (inputs != null || inputPlanner != null)) {
          if (inputPlanner == null) {
            used++;
            var prepared = inputs.get();
            if (prepared == null || !prepared.current()) {
              status = Status.REJECTED;
              return new Progress(status, used);
            }
          } else {
            if (inputPlan == null) {
              inputPlan = Objects.requireNonNull(inputPlanner.get(), "resource admission input plan");
            }
            var result = inputPlan.advance(budget - used);
            if (result.workUsed() > budget - used || result.status() != inputPlan.status()) {
              throw new IllegalStateException("Resource input admission violated its progress contract");
            }
            used += result.workUsed();
            if (result.status() != Status.READY) {
              status = result.status();
              return new Progress(status, used);
            }
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

  private static <D> boolean hasOutputs(Binding<D> binding, RecipeResources resources) {
    return binding.type().hasOutputs(resources.get(binding.type()));
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
    var registries = level.registryAccess();
    var resources = new ArrayList<RecipeResources>(runs.size());
    var eligible = new BitSet();
    for (var run : runs) {
      eligible.or(run.tickStrategies(registries).indices);
      resources.add(run.effectiveTickResources(registries));
    }
    var prepared = new ArrayList<PreparedRecipeResource>();
    if (runs.stream().anyMatch(run -> !run.triggeredResources().isEmpty())) {
      var triggeredItems = TriggeredItemExecution.prepare(owner, runs);
      if (triggeredItems.status() != RecipeResourceExecution.TickStatus.READY) {
        return new TickPreparation(triggeredItems.status(), RecipeResourceDefinitions.ITEM, null);
      }
      // Scheduled IO also needs transactional progress before its due position.
      prepared.add(triggeredItems.prepared());
    }
    for (int index = eligible.nextSetBit(0); index >= 0; index = eligible.nextSetBit(index + 1)) {
      var binding = bindings.get(index);
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
    var strategies = run.tickStrategies(registries);
    return strategies.triggered || !strategies.indices.isEmpty();
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
    var resources = runs.stream().map(run -> run.effectiveTickResources(owner.structureLevel().registryAccess()))
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
    var claims = new OutputClaims();
    for (var run : runs) {
      claims.add(run, ticket.level().registryAccess());
    }
    return claims.plan(ticket);
  }

  private static final class BindingOutputs<D> {
    private final Binding<D> binding;
    private final List<RecipeResourceExecution.Run<D>> claims = new ArrayList<>();

    BindingOutputs(Binding<D> binding) {
      this.binding = binding;
    }

    boolean add(FrozenRecipeRun run, RecipeResources resources) {
      var data = resources.get(binding.type());
      if (binding.type().hasOutputs(data)) {
        claims.add(new RecipeResourceExecution.Run<>(run, data));
        return true;
      }
      return false;
    }
    void plan(RecipeExecutionTicket ticket, List<Supplier<RecipeResourceExecution.Output>> plans) {
      if (!claims.isEmpty()) {
        var captured = Collections.unmodifiableList(claims);
        plans.add(
            () -> Objects.requireNonNull(
                binding.finish().prepare(currentPreparationTicket(ticket), captured),
                "resource output plan"));
      }
    }
  }

  /**
   * Paid declarations survive ticket replacement. Live output planners are
   * rebuilt for each ticket.
   */
  public static final class OutputClaims {
    private final List<Binding<?>> bindings = snapshot();
    private final List<BindingOutputs<?>> outputs = new ArrayList<>();
    private boolean externalOutputs;
    private boolean sealed;

    public OutputClaims() {
      for (var binding : bindings) {
        outputs.add(new BindingOutputs<>(binding));
      }
    }

    public void add(FrozenRecipeRun run, HolderLookup.Provider registries) {
      if (sealed) {
        throw new IllegalStateException("Output declarations already planned");
      }
      var resources = run.resourceData(registries);
      requireSupported(resources, bindings);
      for (int i = 0; i < outputs.size(); i++) {
        if (outputs.get(i).add(run, resources) && i >= BUILTIN_BINDINGS) {
          externalOutputs = true;
        }
      }
    }

    /**
     * External finish strategies may inspect any native output port on the ticket.
     */
    public boolean requiresAllOutputPorts() {
      return externalOutputs;
    }

    public OutputPlanning plan(RecipeExecutionTicket ticket) {
      sealed = true;
      var plans = new ArrayList<Supplier<RecipeResourceExecution.Output>>();
      outputs.forEach(output -> output.plan(ticket, plans));
      return new OutputPlanning(ticket, plans);
    }
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

    public void addDiscardedOutputPorts(Set<RequiredPort> ports) {
      plans.forEach(plan -> plan.addDiscardedOutputPorts(ports));
    }
  }
}
