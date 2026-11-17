package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.RecipeRunStatus;
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
import com.lw.modularmachine.machine.recipe.RecipeTriggeredResources;

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
    for (var group : recipe.ingredientArrays().randomOutputs()) {
      if (!has(PortKind.ITEM_OUTPUT, group.selector())) {
        return Optional.of(PortKind.ITEM_OUTPUT);
      }
    }
    for (var fuel : recipe.itemPayments().fuels()) {
      if (!has(PortKind.ITEM_INPUT, fuel.selector())) {
        return Optional.of(PortKind.ITEM_INPUT);
      }
    }
    for (var row : recipe.triggeredResources().rows()) {
      var kind = RecipeTriggeredResources.kind(row);
      if (!has(kind, row.selector())) {
        return Optional.of(kind);
      }
    }
    for (var group : recipe.ingredientArrays().inputs()) {
      if (group.optional()) {
        continue;
      }
      var kind = switch (group.resource()) {
        case "item" -> PortKind.ITEM_INPUT;
        case "fluid" -> PortKind.FLUID_INPUT;
        case "chemical" -> PortKind.CHEMICAL_INPUT;
        default -> throw new IllegalArgumentException("Unsupported ingredient group resource");
      };
      if (!has(kind, group.selector())) {
        return Optional.of(kind);
      }
    }
    // Chance controls consumption or production, not declared port requirements.
    // Zero-chance inputs still need catalysts; outputs retain frozen item ports.
    for (var input : recipe.inputs()) {
      if (!has(PortKind.ITEM_INPUT, input.selector())) {
        return Optional.of(PortKind.ITEM_INPUT);
      }
    }
    for (var output : recipe.outputs()) {
      if (!output.ignoreOutputCheck() && !has(PortKind.ITEM_OUTPUT, output.selector())) {
        return Optional.of(PortKind.ITEM_OUTPUT);
      }
    }
    if (!recipe.fluidInputs().isEmpty() && !has(PortKind.FLUID_INPUT, recipe.selectors().fluidInput())) {
      return Optional.of(PortKind.FLUID_INPUT);
    }
    if (recipe.fluidOutputRequirements().stream().anyMatch(output -> !output.ignoreOutputCheck())
        && !has(PortKind.FLUID_OUTPUT, recipe.selectors().fluidOutput())) {
      return Optional.of(PortKind.FLUID_OUTPUT);
    }
    if (recipe.energyPerTick() > 0 && !has(PortKind.ENERGY_INPUT, recipe.selectors().energyInput())) {
      return Optional.of(PortKind.ENERGY_INPUT);
    }
    if (recipe.energyOutputPerTick() > 0 && !has(PortKind.ENERGY_OUTPUT, recipe.selectors().energyOutput())) {
      return Optional.of(PortKind.ENERGY_OUTPUT);
    }
    for (var input : recipe.fluidPerTickInputs()) {
      if (!has(PortKind.FLUID_INPUT, input.selector(recipe.selectors().fluidPerTick()))) {
        return Optional.of(PortKind.FLUID_INPUT);
      }
    }
    for (var output : recipe.fluidPerTickOutputs()) {
      if (!has(PortKind.FLUID_OUTPUT, output.selector())) {
        return Optional.of(PortKind.FLUID_OUTPUT);
      }
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
      if (!output.ignoreOutputCheck() && !has(PortKind.CHEMICAL_OUTPUT, output.selector())) {
        return Optional.of(PortKind.CHEMICAL_OUTPUT);
      }
    }
    for (var output : recipe.chemicals().perTickOutputs()) {
      if (!has(PortKind.CHEMICAL_OUTPUT, output.selector())) {
        return Optional.of(PortKind.CHEMICAL_OUTPUT);
      }
    }
    return Optional.empty();
  }

  private boolean has(PortKind kind, String selector) {
    return selectors.getOrDefault(kind, Set.of()).contains(selector);
  }

  public static RecipeRunStatus ordinaryResult(PortKind missing) {
    return switch (missing) {
      case ITEM_INPUT -> RecipeRunStatus.MISSING_ITEM_INPUT;
      case ITEM_OUTPUT -> RecipeRunStatus.MISSING_ITEM_OUTPUT;
      case ENERGY_INPUT -> RecipeRunStatus.MISSING_ENERGY_INPUT;
      case ENERGY_OUTPUT -> RecipeRunStatus.MISSING_ENERGY_OUTPUT;
      case FLUID_INPUT -> RecipeRunStatus.MISSING_FLUID_INPUT;
      case FLUID_OUTPUT -> RecipeRunStatus.MISSING_FLUID_OUTPUT;
      case CHEMICAL_INPUT -> RecipeRunStatus.MISSING_CHEMICAL_INPUT;
      case CHEMICAL_OUTPUT -> RecipeRunStatus.MISSING_CHEMICAL_OUTPUT;
      default -> throw new IllegalArgumentException("Unsupported missing recipe component: " + missing);
    };
  }

  public static FactoryThreadState.Reason factoryReason(PortKind missing) {
    return switch (missing) {
      case ITEM_INPUT -> FactoryThreadState.Reason.MISSING_ITEM_INPUT;
      case ITEM_OUTPUT -> FactoryThreadState.Reason.MISSING_ITEM_OUTPUT;
      case ENERGY_INPUT -> FactoryThreadState.Reason.MISSING_ENERGY_INPUT;
      case ENERGY_OUTPUT -> FactoryThreadState.Reason.MISSING_ENERGY_OUTPUT;
      case FLUID_INPUT -> FactoryThreadState.Reason.MISSING_FLUID_INPUT;
      case FLUID_OUTPUT -> FactoryThreadState.Reason.MISSING_FLUID_OUTPUT;
      case CHEMICAL_INPUT -> FactoryThreadState.Reason.MISSING_CHEMICAL_INPUT;
      case CHEMICAL_OUTPUT -> FactoryThreadState.Reason.MISSING_CHEMICAL_OUTPUT;
      default -> throw new IllegalArgumentException("Unsupported missing recipe component: " + missing);
    };
  }
}
