package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal component availability; quantities and transactions remain separate.
 */
@ApiStatus.Internal
public final class RecipeResourceRequirements {
  private final Map<PortKind, Set<String>> selectors = new EnumMap<>(PortKind.class);

  private RecipeResourceRequirements(RecipeExecutionTicket ticket) {
    ticket.requireServerThread();
    for (var port : ticket.ports()) {
      selectors.computeIfAbsent(port.kind(), ignored -> new HashSet<>()).add(port.selector());
    }
  }

  public static RecipeResourceRequirements capture(RecipeExecutionTicket ticket) {
    return new RecipeResourceRequirements(ticket);
  }

  public static Optional<PortKind> missing(RecipeExecutionTicket ticket, MachineRecipe recipe) {
    return capture(ticket).missing(recipe);
  }

  public static boolean has(RecipeExecutionTicket ticket, PortKind kind, String selector) {
    ticket.requireServerThread();
    return ticket.ports().stream().anyMatch(port -> port.kind() == kind && port.selector().equals(selector));
  }

  public Optional<PortKind> missing(MachineRecipe recipe) {
    // Chance controls consumption or production, not declared port requirements.
    // Zero-chance inputs still need catalysts; outputs retain frozen item ports.
    for (var input : recipe.inputs()) {
      if (!has(PortKind.ITEM_INPUT, input.selector())) {
        return Optional.of(PortKind.ITEM_INPUT);
      }
    }
    for (var output : recipe.outputs()) {
      if (!has(PortKind.ITEM_OUTPUT, output.selector())) {
        return Optional.of(PortKind.ITEM_OUTPUT);
      }
    }
    if (!recipe.fluidInputs().isEmpty() && !has(PortKind.FLUID_INPUT, recipe.selectors().fluidInput())) {
      return Optional.of(PortKind.FLUID_INPUT);
    }
    if (!recipe.fluidOutputs().isEmpty() && !has(PortKind.FLUID_OUTPUT, recipe.selectors().fluidOutput())) {
      return Optional.of(PortKind.FLUID_OUTPUT);
    }
    if (recipe.energyPerTick() > 0 && !has(PortKind.ENERGY_INPUT, recipe.selectors().energyInput())) {
      return Optional.of(PortKind.ENERGY_INPUT);
    }
    if (!recipe.fluidPerTick().isEmpty() && !has(PortKind.FLUID_INPUT, recipe.selectors().fluidPerTick())) {
      return Optional.of(PortKind.FLUID_INPUT);
    }
    for (var input : recipe.chemicals().inputs()) {
      if (!has(PortKind.CHEMICAL_INPUT, input.selector())) {
        return Optional.of(PortKind.CHEMICAL_INPUT);
      }
    }
    for (var input : recipe.chemicals().perTick()) {
      if (!has(PortKind.CHEMICAL_INPUT, input.selector())) {
        return Optional.of(PortKind.CHEMICAL_INPUT);
      }
    }
    for (var output : recipe.chemicals().outputs()) {
      if (!has(PortKind.CHEMICAL_OUTPUT, output.selector())) {
        return Optional.of(PortKind.CHEMICAL_OUTPUT);
      }
    }
    return Optional.empty();
  }

  private boolean has(PortKind kind, String selector) {
    return selectors.getOrDefault(kind, Set.of()).contains(selector);
  }

  public static ItemRunEngine.Result ordinaryResult(PortKind missing) {
    return switch (missing) {
      case ITEM_INPUT -> ItemRunEngine.Result.MISSING_ITEM_INPUT;
      case ITEM_OUTPUT -> ItemRunEngine.Result.MISSING_ITEM_OUTPUT;
      case ENERGY_INPUT -> ItemRunEngine.Result.MISSING_ENERGY_INPUT;
      case FLUID_INPUT -> ItemRunEngine.Result.MISSING_FLUID_INPUT;
      case FLUID_OUTPUT -> ItemRunEngine.Result.MISSING_FLUID_OUTPUT;
      case CHEMICAL_INPUT -> ItemRunEngine.Result.MISSING_CHEMICAL_INPUT;
      case CHEMICAL_OUTPUT -> ItemRunEngine.Result.MISSING_CHEMICAL_OUTPUT;
      default -> throw new IllegalArgumentException("Unsupported missing recipe component: " + missing);
    };
  }

  public static FactoryThreadState.Reason factoryReason(PortKind missing) {
    return switch (missing) {
      case ITEM_INPUT -> FactoryThreadState.Reason.MISSING_ITEM_INPUT;
      case ITEM_OUTPUT -> FactoryThreadState.Reason.MISSING_ITEM_OUTPUT;
      case ENERGY_INPUT -> FactoryThreadState.Reason.MISSING_ENERGY_INPUT;
      case FLUID_INPUT -> FactoryThreadState.Reason.MISSING_FLUID_INPUT;
      case FLUID_OUTPUT -> FactoryThreadState.Reason.MISSING_FLUID_OUTPUT;
      case CHEMICAL_INPUT -> FactoryThreadState.Reason.MISSING_CHEMICAL_INPUT;
      case CHEMICAL_OUTPUT -> FactoryThreadState.Reason.MISSING_CHEMICAL_OUTPUT;
      default -> throw new IllegalArgumentException("Unsupported missing recipe component: " + missing);
    };
  }
}
