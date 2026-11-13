package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.storage.TransactionalFluidTank;
import com.lw.modularmachine.storage.TransactionalItemStackHandler;
import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Internal participant in the existing resource/run commit decision. */
@ApiStatus.Internal
public final class PatternInputTransaction {
  private final Map<PatternProviderBlockEntity, Participant> participants = new LinkedHashMap<>();
  private final Map<PatternInputSource, Map<PatternResourceKey, Long>> consumption = new LinkedHashMap<>();
  private final List<Binding> starts = new ArrayList<>();
  private final List<Binding> releases = new ArrayList<>();
  private final Set<PatternInputSource> sources = new LinkedHashSet<>();
  private boolean acquired;
  private boolean committed;

  private record Binding(StructureService.StructureSubject owner, UUID run, PatternInputSource source) {
  }

  private static final class Participant {
    final PatternInputSource source;
    NonNullList<ItemStack> items;
    FluidStack fluid;
    PatternProviderState.Lease stateLease;
    TransactionalItemStackHandler.Lease itemLease;
    TransactionalFluidTank.Lease fluidLease;
    long checkedStateRevision;
    long checkedResourceRevision;
    Participant(PatternInputSource source) {
      this.source = source;
    }
  }

  private Participant participant(PatternInputSource source) {
    if (acquired) {
      throw new IllegalStateException("Pattern transaction already acquired");
    }
    var previous = participants.get(source.provider());
    if (previous != null && !previous.source.instance().equals(source.instance())) {
      throw new IllegalStateException("Mixed pattern provider instances");
    }
    sources.add(source);
    return participants.computeIfAbsent(source.provider(), ignored -> new Participant(source));
  }

  private void consume(PatternInputSource source, PatternResourceKey key, long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Nonpositive pattern debit");
    }
    participant(source);
    consumption.computeIfAbsent(source, ignored -> new LinkedHashMap<>()).merge(key, amount, Math::addExact);
  }

  public void consumeItem(RecipeExecutionTicket.PortSnapshot port, int slot, long amount) {
    var source = port.patternSource();
    if (!port.patternInput()
        || slot < 0
        || slot >= port.items().size()
        || amount <= 0
        || amount > port.itemAmount(slot)) {
      throw new IllegalStateException("Invalid pattern item debit");
    }
    if (!port.independentPatternStorage()) {
      consume(source, port.patternKey(slot), amount);
      return;
    }
    var participant = participant(source);
    if (participant.items == null) {
      var values = port.items();
      participant.items = NonNullList.withSize(values.size(), ItemStack.EMPTY);
      for (int i = 0; i < values.size(); i++) {
        participant.items.set(i, values.get(i));
      }
    }
    var before = participant.items.get(slot);
    if (before.getCount() < amount) {
      throw new IllegalStateException("Independent item overdraw");
    }
    participant.items.set(slot, before.copyWithCount(Math.toIntExact(before.getCount() - amount)));
  }

  public void consumeFluid(RecipeExecutionTicket.PortSnapshot port, FluidStack amount) {
    if (!port.patternInput() || amount.isEmpty() || !FluidStack.isSameFluidSameComponents(port.fluid(), amount)) {
      throw new IllegalStateException("Invalid pattern fluid debit");
    }
    if (!port.independentPatternStorage()) {
      consume(port.patternSource(), port.patternKey(0), amount.getAmount());
      return;
    }
    var participant = participant(port.patternSource());
    if (participant.fluid == null) {
      participant.fluid = port.fluid();
    }
    if (participant.fluid.getAmount() < amount.getAmount()) {
      throw new IllegalStateException("Independent fluid overdraw");
    }
    participant.fluid = participant.fluid.copyWithAmount(participant.fluid.getAmount() - amount.getAmount());
  }

  public void consumeChemical(RecipeExecutionTicket.PortSnapshot port, ChemicalStackData amount) {
    if (!port.patternInput() || amount.isEmpty() || !port.chemical().chemical().equals(amount.chemical())) {
      throw new IllegalStateException("Invalid pattern chemical debit");
    }
    consume(port.patternSource(), port.patternKey(0), amount.amount());
  }

  public void bindStart(RecipeExecutionTicket ticket, List<FrozenItemRun> runs) {
    var source = ticket.patternSource();
    if (source == null) {
      return;
    }
    participant(source);
    for (var run : runs) {
      starts.add(new Binding(ticket.owner(), run.runId(), source));
    }
  }

  public static PatternInputTransaction release(StructureService.StructureSubject owner, List<FrozenItemRun> runs) {
    var result = new PatternInputTransaction();
    for (var run : runs) {
      var source = PatternRunSources.source(owner, run);
      if (source != null) {
        result.participant(source);
        result.releases.add(new Binding(owner, run.runId(), source));
      }
    }
    return result;
  }

  public void acquire() {
    if (acquired) {
      throw new IllegalStateException("Pattern transaction already acquired");
    }
    for (var participant : participants.values()) {
      participant.checkedStateRevision = participant.source.provider().state().revision();
      participant.checkedResourceRevision = participant.source.provider().resourceRevision();
    }
    if (sources.stream().anyMatch(source -> !source.current())
        || participants.values().stream().anyMatch(
            value -> value.source.provider().state().revision() != value.checkedStateRevision
                || value.source.provider().resourceRevision() != value.checkedResourceRevision)) {
      throw new IllegalStateException("Stale pattern transaction");
    }
    acquired = true;
    for (var participant : participants.values()) {
      var source = participant.source;
      // Unrelated receipts may have changed during a budgeted search. The exact
      // source shares and independent storage were revalidated above; acquire
      // the current revision on this same server thread, without callbacks.
      participant.stateLease = source.provider().state().acquire(source.instance(), participant.checkedStateRevision);
      if (participant.items != null) {
        participant.itemLease = source.provider().independentItems().acquire();
        participant.itemLease.replacePrepared(participant.items);
      }
      if (participant.fluid != null) {
        participant.fluidLease = source.provider().independentFluid().acquire();
        participant.fluidLease.replacePrepared(participant.fluid);
      }
    }
    // Bind before consuming: a fully consumed receipt must remain held by its paid
    // run.
    for (var binding : starts) {
      var source = binding.source();
      if (source.container() != null
          && !participants.get(source.provider()).stateLease.bindRun(source.container(), source.job(), binding.run())) {
        throw new IllegalStateException("Cannot hold pattern receipt");
      }
    }
    for (var entry : consumption.entrySet()) {
      var source = entry.getKey();
      var lease = participants.get(source.provider()).stateLease;
      boolean accepted = source.run() == null ? lease
          .consume(source.container(), source.job(), entry.getValue()) : lease
              .consumeForRun(source.container(), source.job(), source.run(), entry.getValue());
      if (!accepted) {
        throw new IllegalStateException("Pattern receipt overdraw");
      }
    }
    for (var binding : releases) {
      var source = binding.source();
      if (source.container() != null
          && !participants.get(source.provider()).stateLease
              .releaseRun(source.container(), source.job(), binding.run())) {
        throw new IllegalStateException("Cannot release pattern receipt");
      }
    }
  }

  public void resolve(boolean commit) {
    for (var participant : participants.values()) {
      if (participant.itemLease != null) {
        participant.itemLease.resolve(commit);
      }
      if (participant.fluidLease != null) {
        participant.fluidLease.resolve(commit);
      }
      if (participant.stateLease != null) {
        participant.stateLease.resolve(commit);
      }
    }
    if (commit) {
      for (var binding : starts) {
        PatternRunSources.bind(binding.owner(), binding.run(), binding.source());
      }
      for (var binding : releases) {
        PatternRunSources.unbind(binding.owner(), binding.run());
      }
    }
    committed = commit;
  }

  public void publish() {
    if (!committed) {
      return;
    }
    for (var provider : participants.keySet()) {
      try {
        provider.publishResourceChange();
      } catch (RuntimeException failure) {
        ModularMachine.LOGGER.warn("Pattern resource notification failed", failure);
      }
    }
  }
}
