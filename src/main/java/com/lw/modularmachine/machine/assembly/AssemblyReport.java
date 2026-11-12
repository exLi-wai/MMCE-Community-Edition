package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials.Choices;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal placement exceptions followed by one compact, snapshot-based
 * missing-material line.
 */
@ApiStatus.Internal
final class AssemblyReport {
  record Send(boolean done, int work) {
  }

  private record ProblemKey(PlacementOutcome reason, boolean occupied) {
  }

  private record Row(Component example, int count) {
  }

  private static final class ProblemGroup {
    final Component example;
    final Set<BlockPos> positions = new HashSet<>();

    ProblemGroup(Component example) {
      this.example = example;
    }
  }

  private final Map<MachineDefinition.Rule, Choices> candidates = new HashMap<>();
  private final Map<ProblemKey, ProblemGroup> problems = new LinkedHashMap<>();
  private final Map<Choices, Integer> missing = new LinkedHashMap<>();
  private Delivery delivery;

  Choices choices(AssemblyPattern.Part part) {
    return candidates.computeIfAbsent(part.rule(), ignored -> PlacementMaterials.choices(part.states()));
  }

  void missing(AssemblyPattern.Part part) {
    missing(part, choices(part));
  }

  void missing(AssemblyPattern.Part part, Choices choices) {
    if (!choices.items().isEmpty() || !choices.fluids().isEmpty()) {
      missing.merge(choices, 1, Integer::sum);
    }
  }

  void occupied(BlockPos position, BlockState actual) {
    var key = new ProblemKey(PlacementOutcome.REJECTED, true);
    var group = problems.get(key);
    if (group == null) {
      group = new ProblemGroup(
          Component.translatable(
              "message.mmcece.assembly.occupied",
              position.getX(),
              position.getY(),
              position.getZ(),
              actual.getBlock().getName()));
      problems.put(key, group);
    }
    group.positions.add(position.immutable());
  }

  void problem(BlockPos position, PlacementOutcome reason) {
    if (reason == PlacementOutcome.PLACED || reason == PlacementOutcome.MISSING) {
      return;
    }
    var key = new ProblemKey(reason, false);
    var group = problems.computeIfAbsent(key, ignored -> new ProblemGroup(problemMessage(position, reason)));
    group.positions.add(position.immutable());
  }

  static Component problemMessage(BlockPos position, PlacementOutcome reason) {
    return Component.translatable(
        "message.mmcece.assembly.position_problem",
        position.getX(),
        position.getY(),
        position.getZ(),
        Component.translatable("message.mmcece.assembly.reason." + reason.name().toLowerCase(Locale.ROOT)));
  }

  boolean hasProblems() {
    return !problems.isEmpty();
  }

  void nextPass() {
    problems.clear();
    missing.clear();
    delivery = null;
  }

  void finish() {
    finish(null, Map.of());
  }

  void finish(ServerPlayer player, Map<Choices, Integer> remainingDemand) {
    var rows = new ArrayList<Row>(problems.size());
    for (var group : problems.values()) {
      rows.add(new Row(group.example.copy(), group.positions.size()));
    }
    var planning = remainingDemand.isEmpty() ? null : new AssemblyMaterialPlan(remainingDemand, player);
    delivery = new Delivery(List.copyOf(rows), planning);
  }

  void finish(ServerPlayer player, int matched, int total, int placed) {
    finish(player, missing);
  }

  boolean finishing() {
    return delivery != null;
  }

  /**
   * Detach pure messages before disposing the construction job and its
   * reservations.
   */
  Delivery takeDelivery() {
    if (delivery == null) {
      finish();
    }
    var detached = delivery;
    delivery = null;
    problems.clear();
    missing.clear();

    return detached;
  }

  Delivery takeDelivery(ServerPlayer player, Map<Choices, Integer> remainingDemand) {
    finish(player, remainingDemand);

    return takeDelivery();
  }

  boolean send(ServerPlayer player, int limit) {
    return send(player, limit, System.nanoTime() + 1_000_000L, 64).done();
  }

  Send send(ServerPlayer player, int limit, long deadline, int workBudget) {
    return delivery == null ? new Send(true, 0) : delivery.send(player, limit, deadline, workBudget);
  }

  void cancel() {
    if (delivery != null) {
      delivery.cancel();
    }
    problems.clear();
    missing.clear();
    candidates.clear();
    delivery = null;
  }

  /**
   * Internal snapshot delivery; it does not keep live player, world, owner or job
   * references.
   */
  @ApiStatus.Internal
  static final class Delivery {
    private final List<Row> rows;
    private final AssemblyMaterialPlan planning;
    private int cursor;
    private AssemblyMaterialPlan.Result plan;
    private Iterator<AssemblyMaterialPlan.Shortage> shortages;
    private AssemblyMaterialPlan.Shortage shortage;
    private MutableComponent shortageNames;
    private final MutableComponent summary = Component.empty();
    private int itemName, fluidName, entries;
    private boolean summaryReady, summarySent;
    private Component summaryMessage;

    private Delivery(List<Row> rows, AssemblyMaterialPlan planning) {
      this.rows = rows;
      this.planning = planning;
      summaryReady = planning == null;
      summarySent = planning == null;
    }

    boolean done() {
      return cursor == rows.size() && summarySent;
    }

    Send send(ServerPlayer player, int limit, long deadline, int workBudget) {
      int work = 0, sent = 0;
      while (cursor < rows.size() && work < workBudget && sent < limit && System.nanoTime() < deadline) {
        var row = rows.get(cursor);
        var message = row.count() == 1 ? row.example() : Component
            .translatable("message.mmcece.assembly.problem_group", row.example(), row.count());
        player.sendSystemMessage(message);
        cursor++;
        sent++;
        work++;
      }
      if (cursor < rows.size() || work >= workBudget || System.nanoTime() >= deadline) {
        return new Send(done(), work);
      }
      if (!summaryReady) {
        work += prepareSummary(deadline, workBudget - work);
      }
      if (summaryReady && !summarySent && sent < limit && work < workBudget && System.nanoTime() < deadline) {
        if (summaryMessage != null) {
          player.sendSystemMessage(summaryMessage);
          work++;
        }
        summarySent = true;
      }

      return new Send(done(), work);
    }

    private int prepareSummary(long deadline, int workBudget) {
      int work = 0;
      if (plan == null) {
        try {
          var advanced = planning.advance(deadline, workBudget);
          work += advanced.work();
          plan = advanced.result();
        } catch (RuntimeException failure) {
          ModularMachine.LOGGER.warn("Assembly missing-material summary failed", failure);
          summaryMessage = Component.translatable("message.mmcece.assembly.missing_unavailable");
          summaryReady = true;
          return Math.min(workBudget, Math.max(1, work));
        }
        if (plan == null) {
          return work;
        }
        shortages = plan.shortages.iterator();
      }
      while (work < workBudget && System.nanoTime() < deadline) {
        work++;
        if (shortage == null) {
          if (!shortages.hasNext()) {
            summaryMessage = entries == 0 ? null : Component
                .translatable("message.mmcece.assembly.missing_compact", summary);
            summaryReady = true;
            return work;
          }
          shortage = shortages.next();
          shortageNames = Component.empty();
          itemName = 0;
          fluidName = 0;
        }
        if (!shortage.fluid().isEmpty()) {
          appendShortage(
              Component.translatable(
                  "message.mmcece.assembly.missing_compact_fluid",
                  shortage.fluid().getHoverName(),
                  shortage.millibuckets()));
          continue;
        }
        var choices = shortage.choices();
        if (itemName < choices.items().size()) {
          appendName(Component.translatable(choices.items().get(itemName++).getDescriptionId()));
          continue;
        }
        if (fluidName < choices.fluids().size()) {
          Component name = new FluidStack(choices.fluids().get(fluidName++), FluidType.BUCKET_VOLUME).getHoverName();
          if (!choices.items().isEmpty()) {
            name = Component
                .translatable("message.mmcece.assembly.missing_fluid_option", name, FluidType.BUCKET_VOLUME);
          }
          appendName(name);
          continue;
        }
        if (choices.items().isEmpty() && choices.fluids().isEmpty()) {
          shortage = null;
          continue;
        }
        Component names = shortageNames;
        if (choices.items().size() + choices.fluids().size() > 1) {
          names = Component.literal("(").append(names).append(")");
        }
        Component entry;
        if (choices.items().isEmpty()) {
          entry = Component
              .translatable("message.mmcece.assembly.missing_compact_fluid", names, shortage.millibuckets());
        } else {
          entry = Component.translatable("message.mmcece.assembly.missing_compact_entry", names, shortage.positions());
        }
        appendShortage(entry);
      }

      return work;
    }

    private void appendName(Component name) {
      if (!shortageNames.getSiblings().isEmpty()) {
        shortageNames.append(" / ");
      }
      shortageNames.append(name);
    }

    private void appendShortage(Component entry) {
      if (entries++ > 0) {
        summary.append(Component.translatable("message.mmcece.assembly.missing_separator"));
      }
      summary.append(entry);
      shortage = null;
      shortageNames = null;
    }

    void cancel() {
      if (planning != null) {
        planning.cancel();
      }
    }
  }
}
