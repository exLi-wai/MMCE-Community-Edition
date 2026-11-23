package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.RecipeRunStatus;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    for (var port : recipe.requiredPorts()) {
      if (!has(port.kind(), port.selector())) {
        return Optional.of(port.kind());
      }
    }
    return Optional.empty();
  }

  public record RequiredPort(PortKind kind, String selector) {
  }

  static Set<RequiredPort> forCandidate(RecipeCatalog catalog, RecipeCandidate<ItemResourceKey> candidate, MachineRecipe definition) {
    if (definition != null) {
      return Set.copyOf(definition.requiredPorts());
    }
    var ports = new HashSet<RequiredPort>();
    String id = candidate.id();
    if (!catalog.fluidInputs(id).isEmpty()) {
      ports.add(new RequiredPort(PortKind.FLUID_INPUT, catalog.selectors(id).fluidInput()));
    }
    if (!catalog.fluidOutputs(id).isEmpty()) {
      ports.add(new RequiredPort(PortKind.FLUID_OUTPUT, catalog.selectors(id).fluidOutput()));
    }
    candidate.inputs().forEach(input -> ports.add(new RequiredPort(PortKind.ITEM_INPUT, input.key().selector())));
    candidate.outputs().stream().filter(output -> !output.ignoreOutputCheck())
        .forEach(output -> ports.add(new RequiredPort(PortKind.ITEM_OUTPUT, output.key().selector())));
    return Set.copyOf(ports);
  }

  /**
   * Compile once, preserving declaration order for the first missing-port
   * diagnosis.
   */
  public static List<RequiredPort> declared(MachineRecipe recipe) {
    var ports = new LinkedHashSet<RequiredPort>();
    for (var group : recipe.ingredientArrays().randomOutputs()) {
      ports.add(new RequiredPort(PortKind.ITEM_OUTPUT, group.selector()));
    }
    for (var fuel : recipe.itemPayments().fuels()) {
      ports.add(new RequiredPort(PortKind.ITEM_INPUT, fuel.selector()));
    }
    for (var row : recipe.triggeredResources().rows()) {
      var kind = RecipeTriggeredResources.kind(row);
      ports.add(new RequiredPort(kind, row.selector()));
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
      ports.add(new RequiredPort(kind, group.selector()));
    }
    // Chance controls consumption or production, not declared port requirements.
    // Zero-chance inputs still need catalysts; outputs retain frozen item ports.
    for (var input : recipe.inputs()) {
      ports.add(new RequiredPort(PortKind.ITEM_INPUT, input.selector()));
    }
    for (var output : recipe.outputs()) {
      if (!output.ignoreOutputCheck()) {
        ports.add(new RequiredPort(PortKind.ITEM_OUTPUT, output.selector()));
      }
    }
    if (!recipe.fluidInputRequirements().isEmpty()) {
      ports.add(new RequiredPort(PortKind.FLUID_INPUT, recipe.selectors().fluidInput()));
    }
    if (recipe.fluidOutputRequirements().stream().anyMatch(output -> !output.ignoreOutputCheck())) {
      ports.add(new RequiredPort(PortKind.FLUID_OUTPUT, recipe.selectors().fluidOutput()));
    }
    if (recipe.energyPerTick() > 0) {
      ports.add(new RequiredPort(PortKind.ENERGY_INPUT, recipe.selectors().energyInput()));
    }
    if (recipe.energyOutputPerTick() > 0) {
      ports.add(new RequiredPort(PortKind.ENERGY_OUTPUT, recipe.selectors().energyOutput()));
    }
    for (var input : recipe.fluidPerTickInputs()) {
      ports.add(new RequiredPort(PortKind.FLUID_INPUT, input.selector(recipe.selectors().fluidPerTick())));
    }
    for (var output : recipe.fluidPerTickOutputs()) {
      ports.add(new RequiredPort(PortKind.FLUID_OUTPUT, output.selector()));
    }
    for (var input : recipe.chemicals().inputs()) {
      ports.add(new RequiredPort(PortKind.CHEMICAL_INPUT, input.selector()));
    }
    for (var input : recipe.chemicals().perTick()) {
      ports.add(new RequiredPort(PortKind.CHEMICAL_INPUT, input.selector()));
    }
    for (var output : recipe.chemicals().outputs()) {
      if (!output.ignoreOutputCheck()) {
        ports.add(new RequiredPort(PortKind.CHEMICAL_OUTPUT, output.selector()));
      }
    }
    for (var output : recipe.chemicals().perTickOutputs()) {
      ports.add(new RequiredPort(PortKind.CHEMICAL_OUTPUT, output.selector()));
    }
    return List.copyOf(ports);
  }

  private boolean has(PortKind kind, String selector) {
    return selectors.getOrDefault(kind, Set.of()).contains(selector);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RecipeResourceRequirements requirements && selectors.equals(requirements.selectors);
  }

  @Override
  public int hashCode() {
    return selectors.hashCode();
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
