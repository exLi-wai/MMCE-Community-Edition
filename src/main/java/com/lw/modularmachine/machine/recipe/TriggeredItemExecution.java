package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlan;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.structure.StructureService;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import static com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution.TickResult.failed;
import java.util.stream.Collectors;

/**
 * Main-thread item trigger preparation; full demands prove availability before
 * sampled writes join the owner's progress transaction. Output continuations
 * retain detached images and weak port identities, never live owner tickets.
 */
@ApiStatus.Internal
public final class TriggeredItemExecution {
  private record Slot(Object inventory, int slot) {
  }

  private record Input(RecipeExecutionTicket ticket, TriggeredResourceData.Row row, long amount) {
  }

  private record Debit(RecipePortSnapshot port, int slot, long amount) {
  }

  private sealed interface DemandCapture {
  }

  private record RejectedDemands(RecipeResourceExecution.TickStatus status) implements DemandCapture {
  }

  private record CapturedDemands(
      List<RecipeExecutionTicket> proofs,
      List<Input> fullInputs,
      List<Input> sampledInputs,
      List<RecipeCandidate.Output<ItemResourceKey>> outputs) implements DemandCapture {
    private CapturedDemands {
      proofs = List.copyOf(proofs);
      fullInputs = List.copyOf(fullInputs);
      sampledInputs = List.copyOf(sampledInputs);
      outputs = List.copyOf(outputs);
    }
  }

  private record Image(
      WeakReference<MachinePortBlockEntity> identity,
      int tank,
      long revision,
      String selector,
      ItemResourceKey[] keys,
      long[] amounts,
      List<Integer> limits) {
    static Image capture(RecipePortSnapshot port) {
      int slots = port.itemSlotCount();
      var keys = new ItemResourceKey[slots];
      var amounts = new long[slots];
      for (int slot = 0; slot < slots; slot++) {
        keys[slot] = port.itemKey(slot);
        amounts[slot] = port.itemAmount(slot);
      }
      return new Image(
          new WeakReference<>(port.identity()),
          port.tankIndex(),
          port.revision(),
          port.selector(),
          keys,
          amounts,
          port.slotLimits());
    }

    boolean matches(RecipePortSnapshot port) {
      if (identity.get() != port.identity()
          || tank != port.tankIndex()
          || revision != port.revision()
          || !selector.equals(port.selector())
          || keys.length != port.itemSlotCount()
          || !limits.equals(port.slotLimits())) {
        return false;
      }
      for (int slot = 0; slot < keys.length; slot++) {
        if (amounts[slot] != port.itemAmount(slot) || !Objects.equals(keys[slot], port.itemKey(slot))) {
          return false;
        }
      }
      return true;
    }
  }

  private static final class Pending implements TickOutputWork.Plan {
    final long structureRevision, definitionEpoch;
    final List<FrozenRecipeRun> runs;
    final List<Image> images;
    final List<RecipeCandidate.Output<ItemResourceKey>> claims;
    final ItemOutputLayout layout;
    final RecipePlanner.Session<ItemResourceKey> session;
    RecipePlanner.Status status = RecipePlanner.Status.BUDGET_EXHAUSTED;
    RecipePlan<ItemResourceKey> selected;

    Pending(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<RecipeCandidate.Output<ItemResourceKey>> claims) {
      structureRevision = ticket.structureRevision();
      definitionEpoch = ticket.definitionEpoch();
      this.runs = List.copyOf(runs);
      images = ticket.ports().stream().filter(port -> port.kind() == PortKind.ITEM_OUTPUT).map(Image::capture).toList();
      this.claims = List.copyOf(claims);
      layout = new ItemOutputLayout(
          ticket,
          claims.stream().map(RecipeCandidate.Output::key).collect(Collectors.toSet()));
      session = RecipePlanner.prepareOutputs(layout.snapshot(), RecipeCandidate.outputOnly("triggered_items", claims));
    }

    boolean matches(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<RecipeCandidate.Output<ItemResourceKey>> claims) {
      return structureRevision == ticket.structureRevision()
          && definitionEpoch == ticket.definitionEpoch()
          && this.runs.equals(runs)
          && this.claims.equals(claims)
          && sameImages(images, ticket);
    }

    @Override
    public int advance(int budget) {
      var result = session.advance(budget);
      status = result.status();
      selected = result.plan().orElse(null);
      return result.workUsed();
    }

    @Override
    public boolean pending() {
      return status == RecipePlanner.Status.BUDGET_EXHAUSTED;
    }
  }

  private TriggeredItemExecution() {
  }

  public static RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<FrozenRecipeRun> runs) {
    if (runs.stream().allMatch(run -> RecipeTriggeredResources.dueRows(run, "item").isEmpty())) {
      return RecipeResourceExecution.TickResult.ready(empty());
    }
    var ticket = RecipeExecutionTicket.captureTickItems(owner).orElse(null);
    if (ticket == null) {
      return failed(RecipeResourceExecution.TickStatus.STALE);
    }

    return switch (captureDemands(ticket, runs)) {
      case RejectedDemands rejected -> failed(rejected.status());
      case CapturedDemands captured -> prepareChanges(owner, ticket, runs, captured);
    };
  }

  private static DemandCapture captureDemands(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs) {
    var fullInputs = new ArrayList<Input>();
    var sampledInputs = new ArrayList<Input>();
    var proofs = new ArrayList<RecipeExecutionTicket>();
    var totals = new LinkedHashMap<ItemResourceKey, Long>();
    for (var run : runs) {
      var rows = RecipeTriggeredResources.dueRows(run, "item");
      if (rows.isEmpty()) {
        continue;
      }
      var source = ticket.forRun(run).orElse(null);
      if (source == null || !run.matchesInputGroups(source)) {
        return new RejectedDemands(RecipeResourceExecution.TickStatus.STALE);
      }
      proofs.add(source);
      for (int index = 0; index < rows.size(); index++) {
        var row = rows.get(index);
        var selected = row.io() == RecipeModifier.Io.INPUT ? source : ticket;
        if (!RecipeResourceRequirements.has(selected, RecipeTriggeredResources.kind(row), row.selector())) {
          return new RejectedDemands(
              row.io() == RecipeModifier.Io.INPUT ? RecipeResourceExecution.TickStatus.MISSING_INPUT : RecipeResourceExecution.TickStatus.MISSING_OUTPUT);
        }
        long actual = RecipeTriggeredResources.sampledAmount(run, row, index);
        if (row.io() == RecipeModifier.Io.INPUT) {
          fullInputs.add(new Input(source, row, row.amount()));
          sampledInputs.add(new Input(source, row, actual));
        } else if (actual > 0) {
          totals.merge(
              ItemResourceKey.of(row.item().orElseThrow(), ticket.level().registryAccess())
                  .withSelector(row.selector()),
              actual,
              Math::addExact);
        }
      }
    }

    var outputs = totals.entrySet().stream()
        .map(entry -> new RecipeCandidate.Output<>(entry.getKey(), entry.getValue())).toList();
    return new CapturedDemands(proofs, fullInputs, sampledInputs, outputs);
  }

  private static RecipeResourceExecution.TickResult prepareChanges(StructureService.StructureSubject owner, RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, CapturedDemands demands) {
    // Full demand is proved independently of the draw; only sampled debits
    // become private replacement images or pattern receipt changes.
    if (planInputs(demands.fullInputs()) == null) {
      return failed(RecipeResourceExecution.TickStatus.INSUFFICIENT);
    }
    var debits = planInputs(demands.sampledInputs());
    if (debits == null) {
      return failed(RecipeResourceExecution.TickStatus.STALE);
    }
    Pending pending = null;
    if (!demands.outputs().isEmpty()) {
      pending = planOutputs(owner, ticket, runs, demands.outputs());
      if (pending.status != RecipePlanner.Status.SELECTED) {
        return failed(
            pending.status == RecipePlanner.Status.NO_MATCH ? RecipeResourceExecution.TickStatus.OUTPUT_BLOCKED : RecipeResourceExecution.TickStatus.STALE);
      }
    }
    var changes = inputChanges(debits);
    if (pending != null) {
      addOutputChanges(ticket, pending, changes);
    }
    return RecipeResourceExecution.TickResult
        .ready(new Batch(ticket, demands.proofs(), debits, List.copyOf(changes.values())));
  }

  private static void addOutputChanges(RecipeExecutionTicket ticket, Pending pending, Map<Object, Change> changes) {
    var additions = pending.layout.prepare(pending.selected);
    for (var entry : additions.entrySet()) {
      var port = ticket.ports().stream().filter(value -> value.partId().equals(entry.getKey())).findFirst()
          .orElseThrow();
      if (changes.putIfAbsent(port.physicalIdentity(), new Change(port, entry.getValue())) != null) {
        throw new IllegalStateException("Triggered input and output share physical item storage");
      }
    }
  }

  private static Map<Object, Change> inputChanges(List<Debit> debits) {
    var changes = new LinkedHashMap<Object, Change>();
    for (var debit : debits) {
      if (debit.port().patternInput()) {
        continue;
      }
      var change = changes.computeIfAbsent(
          debit.port().physicalIdentity(),
          ignored -> new Change(debit.port(), debit.port().copyItemContents()));
      var before = change.items().get(debit.slot());
      change.items().set(debit.slot(), before.copyWithCount(Math.toIntExact(before.getCount() - debit.amount())));
    }

    return changes;
  }

  private static Pending planOutputs(StructureService.StructureSubject owner, RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<RecipeCandidate.Output<ItemResourceKey>> claims) {
    return TickOutputWork.forOwner(owner).visit(
        ticket.level().getGameTime(),
        Pending.class,
        runs.getFirst().runId(),
        plan -> plan.matches(ticket, runs, claims),
        () -> new Pending(ticket, runs, claims));
  }

  private static boolean sameImages(List<Image> images, RecipeExecutionTicket ticket) {
    int index = 0;
    for (var port : ticket.ports()) {
      if (port.kind() == PortKind.ITEM_OUTPUT && (index >= images.size() || !images.get(index++).matches(port))) {
        return false;
      }
    }
    return index == images.size();
  }

  private static List<Debit> planInputs(List<Input> inputs) {
    var reserved = new HashMap<Slot, Long>();
    var result = new ArrayList<Debit>();
    for (var input : inputs) {
      long remaining = input.amount();
      var prototype = input.row().item().orElseThrow();
      for (var port : input.ticket().ports()) {
        if (port.kind() != PortKind.ITEM_INPUT || !port.selector().equals(input.row().selector())) {
          continue;
        }
        for (int slot = 0; slot < port.itemSlotCount() && remaining > 0; slot++) {
          var key = port.itemKey(slot);
          if (key == null
              || !(input.row().match().equals("item") ? key.ignoringComponents() : key).matches(prototype)) {
            continue;
          }
          var address = new Slot(port.physicalIdentity(), slot);
          long used = reserved.getOrDefault(address, 0L);
          long take = Math.min(remaining, Math.max(0, port.itemAmount(slot) - used));
          if (take > 0) {
            reserved.put(address, Math.addExact(used, take));
            result.add(new Debit(port, slot, take));
            remaining -= take;
          }
        }
        if (remaining == 0) {
          break;
        }
      }
      if (remaining != 0) {
        return null;
      }
    }
    return List.copyOf(result);
  }

  private record Change(RecipePortSnapshot port, NonNullList<ItemStack> items) {
  }

  private static final class Batch implements PreparedRecipeResource {
    private final RecipeExecutionTicket output;
    private final List<RecipeExecutionTicket> proofs;
    private final List<Debit> debits;
    private final List<Change> changes;
    private final RecipeResourceTransaction transaction = new RecipeResourceTransaction();
    private final List<MachinePortBlockEntity.ItemStartLease> leases = new ArrayList<>();

    Batch(RecipeExecutionTicket output, List<RecipeExecutionTicket> proofs, List<Debit> debits, List<Change> changes) {
      this.output = output;
      this.proofs = List.copyOf(proofs);
      this.debits = List.copyOf(debits);
      this.changes = List.copyOf(changes);
    }

    @Override
    public boolean current() {
      return RecipeCommitGuard.validateViews(output, proofs);
    }

    @Override
    public void addPatterns(PatternInputTransaction patterns) {
      for (var debit : debits) {
        if (debit.port().patternInput()) {
          patterns.consumeItem(debit.port(), debit.slot(), debit.amount());
        }
      }
    }

    @Override
    public void acquire() {
      var phase = transaction.phase();
      for (var change : changes) {
        var lease = phase.enlist(
            change.port().identity().acquireItemStart(change.port().revision()),
            MachinePortBlockEntity.ItemStartLease::resolve);
        leases.add(lease);
        lease.replacePrepared(change.items());
      }
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      RecipeResourceTransaction.publishAll(
          leases,
          MachinePortBlockEntity.ItemStartLease::publish,
          error -> ModularMachine.LOGGER.warn("Committed trigger item notification failed", error));
    }
  }

  private static PreparedRecipeResource empty() {
    return new PreparedRecipeResource() {
      @Override
      public void acquire() {
      }

      @Override
      public void resolve(boolean commit) {
      }

      @Override
      public void publish() {
      }
    };
  }
}
