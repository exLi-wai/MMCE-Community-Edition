package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials.Choices;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal, final-pass diagnostics. Delivery reads no world or inventory state.
 */
@ApiStatus.Internal
final class AssemblyReport {
  record Send(boolean done, int work) {
  }

  private record Message(Component value, int work, boolean done) {
  }

  private final Map<MachineDefinition.Rule, Choices> candidates = new HashMap<>();
  private final Map<Choices, Integer> missing = new LinkedHashMap<>();
  private final List<Component> problems = new ArrayList<>();
  private int missingPositions;
  private Delivery delivery;
  private AssemblyMaterialPlan planning;
  private AssemblyMaterialPlan.Result plan;
  private Iterator<Map.Entry<Choices, Integer>> fallback;
  private final List<AssemblyMaterialPlan.Shortage> fallbackShortages = new ArrayList<>();
  private Component pendingMessage;

  Choices choices(AssemblyPattern.Part part) {
    return candidates.computeIfAbsent(part.rule(), ignored -> PlacementMaterials.choices(part.states()));
  }

  void missing(AssemblyPattern.Part part) {
    missing(part, choices(part));
  }

  void missing(AssemblyPattern.Part part, Choices choices) {
    if (choices.items().isEmpty() && choices.fluids().isEmpty()) {
      problem(part.position(), PlacementOutcome.MANUAL);
      return;
    }
    missing.merge(choices, 1, Integer::sum);
    missingPositions++;
  }

  void occupied(BlockPos position, BlockState actual) {
    problems.add(
        Component.translatable(
            "message.mmcece.assembly.occupied",
            position.getX(),
            position.getY(),
            position.getZ(),
            actual.getBlock().getName()));
  }

  void problem(BlockPos position, PlacementOutcome reason) {
    problems.add(problemMessage(position, reason));
  }

  static Component problemMessage(BlockPos position, PlacementOutcome reason) {
    return Component.translatable(
        "message.mmcece.assembly.position_problem",
        position.getX(),
        position.getY(),
        position.getZ(),
        Component.translatable("message.mmcece.assembly.reason." + reason.name().toLowerCase(Locale.ROOT)));
  }

  void nextPass() {
    missing.clear();
    problems.clear();
    missingPositions = 0;
  }

  void finish(ServerPlayer player, int matched, int total, int placed) {
    planning = new AssemblyMaterialPlan(missing, player);
    delivery = new Delivery(matched, total, placed);
  }

  boolean finishing() {
    return delivery != null;
  }

  boolean send(ServerPlayer player, int limit) {
    return send(player, limit, System.nanoTime() + 1_000_000L, 64).done();
  }

  Send send(ServerPlayer player, int limit, long deadline, int workBudget) {
    int work = 0;
    if (plan == null) {
      if (fallback == null) {
        try {
          var advanced = planning.advance(deadline, workBudget);
          plan = advanced.result();
          work += advanced.work();
        } catch (RuntimeException failure) {
          com.lw.modularmachine.ModularMachine.LOGGER.warn("Assembly material report planning failed", failure);
          fallback = missing.entrySet().iterator();
        }
      }
      if (fallback != null) {
        while (fallback.hasNext() && work < workBudget && System.nanoTime() < deadline) {
          var entry = fallback.next();
          fallbackShortages.add(
              new AssemblyMaterialPlan.Shortage(
                  entry.getKey(),
                  entry.getValue(),
                  FluidStack.EMPTY,
                  (long) entry.getValue() * FluidType.BUCKET_VOLUME));
          work++;
        }
        if (!fallback.hasNext()) {
          plan = new AssemblyMaterialPlan.Result(Map.of(), fallbackShortages, true);
        }
      }
      if (plan == null) {
        return new Send(false, work);
      }
      delivery.materials = plan.shortages.iterator();
    }
    int sent = 0;
    while (sent < limit && work < workBudget && System.nanoTime() < deadline) {
      if (pendingMessage == null) {
        var message = delivery.next(deadline, workBudget - work);
        work += message.work();
        if (message.done()) {
          return new Send(true, work);
        }
        pendingMessage = message.value();
        if (pendingMessage == null) {
          break;
        }
      }
      if (work >= workBudget || System.nanoTime() >= deadline) {
        break;
      }
      player.sendSystemMessage(pendingMessage);
      pendingMessage = null;
      sent++;
      work++;
    }

    return new Send(false, work);
  }

  void cancel() {
    if (planning != null) {
      planning.cancel();
    }
  }

  private final class Delivery {
    final int matched, total, placed;
    Iterator<AssemblyMaterialPlan.Shortage> materials;
    final Iterator<Component> issues = problems.iterator();
    AssemblyMaterialPlan.Shortage material;
    MutableComponent materialNames;
    int itemName, fluidName;
    int phase;

    Delivery(int matched, int total, int placed) {
      this.matched = matched;
      this.total = total;
      this.placed = placed;
    }

    Message next(long deadline, int workBudget) {
      int work = 0;
      while (work < workBudget && System.nanoTime() < deadline) {
        work++;
        switch (phase) {
          case 0 -> {
            phase++;
            var message = matched == total ? Component
                .translatable("message.mmcece.assembly.complete", matched, total, placed) : Component.translatable(
                    "message.mmcece.assembly.finished_partial",
                    matched,
                    total,
                    placed,
                    missingPositions,
                    problems.size());
            return new Message(message, work, false);
          }
          case 1 -> {
            phase++;
            if (plan.incomplete) {
              return new Message(
                  Component.translatable("message.mmcece.assembly.material_snapshot_incomplete"),
                  work,
                  false);
            }
          }
          case 2 -> {
            phase++;
            if (!plan.shortages.isEmpty()) {
              return new Message(Component.translatable("message.mmcece.assembly.net_missing_header"), work, false);
            }
          }
          case 3 -> {
            if (material == null) {
              if (!materials.hasNext()) {
                phase++;
                continue;
              }
              material = materials.next();
              materialNames = Component.empty();
              itemName = 0;
              fluidName = 0;
              if (!material.fluid().isEmpty()) {
                var message = Component.translatable(
                    "message.mmcece.assembly.net_missing_fluid",
                    material.fluid().getHoverName(),
                    material.millibuckets());
                material = null;
                return new Message(message, work, false);
              }
              continue;
            }
            var choices = material.choices();
            if (itemName < choices.items().size()) {
              appendName(Component.translatable(choices.items().get(itemName++).getDescriptionId()));
              continue;
            }
            if (fluidName < choices.fluids().size()) {
              appendName(new FluidStack(choices.fluids().get(fluidName++), FluidType.BUCKET_VOLUME).getHoverName());
              if (!choices.items().isEmpty()) {
                materialNames.append(" (" + FluidType.BUCKET_VOLUME + " mB)");
              }
              continue;
            }
            Component message;
            if (!choices.fluids().isEmpty()) {
              message = choices.items().isEmpty() ? Component.translatable(
                  "message.mmcece.assembly.net_missing_fluid_choices",
                  materialNames,
                  material.positions(),
                  material.millibuckets()) : Component
                      .translatable("message.mmcece.assembly.missing_mixed", materialNames, material.positions());
            } else {
              message = Component.translatable(
                  choices.items()
                      .size() == 1 ? "message.mmcece.assembly.missing_item" : "message.mmcece.assembly.missing_choices",
                  materialNames,
                  material.positions());
            }
            material = null;
            materialNames = null;
            return new Message(message, work, false);
          }
          case 4 -> {
            phase++;
            if (!problems.isEmpty()) {
              return new Message(Component.translatable("message.mmcece.assembly.problems_header"), work, false);
            }
          }
          case 5 -> {
            if (issues.hasNext()) {
              return new Message(issues.next(), work, false);
            }
            phase++;
          }
          case 6 -> {
            phase++;
            if (matched < total) {
              return new Message(Component.translatable("message.mmcece.assembly.retry"), work, false);
            }
          }
          default -> {
            return new Message(null, work, true);
          }
        }
      }

      return new Message(null, work, false);
    }

    private void appendName(Component name) {
      if (!materialNames.getSiblings().isEmpty()) {
        materialNames.append(" / ");
      }
      materialNames.append(name);
    }
  }
}
