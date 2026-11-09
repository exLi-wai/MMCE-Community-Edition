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
  private final Map<MachineDefinition.Rule, Choices> candidates = new HashMap<>();
  private final Map<Choices, Integer> missing = new LinkedHashMap<>();
  private final List<Component> problems = new ArrayList<>();
  private int missingPositions;
  private Delivery delivery;
  private AssemblyMaterialPlan planning;
  private AssemblyMaterialPlan.Result plan;

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
    if (plan == null) {
      try {
        plan = planning.advance();
      } catch (RuntimeException failure) {
        com.lw.modularmachine.ModularMachine.LOGGER.warn("Assembly material report planning failed", failure);
        var shortage = missing.entrySet().stream()
            .map(
                entry -> new AssemblyMaterialPlan.Shortage(
                    entry.getKey(),
                    entry.getValue(),
                    FluidStack.EMPTY,
                    (long) entry.getValue() * FluidType.BUCKET_VOLUME))
            .toList();
        plan = new AssemblyMaterialPlan.Result(Map.of(), shortage, true);
      }
      if (plan == null) {
        return false;
      }
      delivery.materials = plan.shortages.iterator();
    }
    for (int sent = 0; sent < limit; sent++) {
      var message = delivery.next();
      if (message == null) {
        return true;
      }
      player.sendSystemMessage(message);
    }
    return false;
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
    int phase;

    Delivery(int matched, int total, int placed) {
      this.matched = matched;
      this.total = total;
      this.placed = placed;
    }

    Component next() {
      while (true) {
        switch (phase) {
          case 0 -> {
            phase++;
            return matched == total ? Component
                .translatable("message.mmcece.assembly.complete", matched, total, placed) : Component.translatable(
                    "message.mmcece.assembly.finished_partial",
                    matched,
                    total,
                    placed,
                    missingPositions,
                    problems.size());
          }
          case 1 -> {
            phase++;
            if (plan.incomplete) {
              return Component.translatable("message.mmcece.assembly.material_snapshot_incomplete");
            }
          }
          case 2 -> {
            phase++;
            if (!plan.shortages.isEmpty()) {
              return Component.translatable("message.mmcece.assembly.net_missing_header");
            }
          }
          case 3 -> {
            if (materials.hasNext()) {
              var entry = materials.next();
              var names = Component.empty();
              var choices = entry.choices();
              if (!entry.fluid().isEmpty()) {
                return Component.translatable(
                    "message.mmcece.assembly.net_missing_fluid",
                    entry.fluid().getHoverName(),
                    entry.millibuckets());
              }
              for (var item : choices.items()) {
                if (!names.getSiblings().isEmpty()) {
                  names.append(" / ");
                }
                names.append(Component.translatable(item.getDescriptionId()));
              }
              for (var fluid : choices.fluids()) {
                if (!names.getSiblings().isEmpty()) {
                  names.append(" / ");
                }
                names.append(new FluidStack(fluid, FluidType.BUCKET_VOLUME).getHoverName());
                if (!choices.items().isEmpty()) {
                  names.append(" (" + FluidType.BUCKET_VOLUME + " mB)");
                }
              }
              if (!choices.fluids().isEmpty()) {
                return choices.items().isEmpty() ? Component.translatable(
                    "message.mmcece.assembly.net_missing_fluid_choices",
                    names,
                    entry.positions(),
                    entry.millibuckets()) : Component
                        .translatable("message.mmcece.assembly.missing_mixed", names, entry.positions());
              }
              return Component.translatable(
                  choices.items()
                      .size() == 1 ? "message.mmcece.assembly.missing_item" : "message.mmcece.assembly.missing_choices",
                  names,
                  entry.positions());
            }
            phase++;
          }
          case 4 -> {
            phase++;
            if (!problems.isEmpty()) {
              return Component.translatable("message.mmcece.assembly.problems_header");
            }
          }
          case 5 -> {
            if (issues.hasNext()) {
              return issues.next();
            }
            phase++;
          }
          case 6 -> {
            phase++;
            if (matched < total) {
              return Component.translatable("message.mmcece.assembly.retry");
            }
          }
          default -> {
            return null;
          }
        }
      }
    }
  }
}
