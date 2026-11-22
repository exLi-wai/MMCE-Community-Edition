package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Exact replenishment targets for one unmodified instance of a native recipe.
 */
public final class NetworkPortRecipePlan {
  public enum Error {
    NONE,
    NO_INPUT,
    UNSUPPORTED,
    TOO_MANY,
    AMOUNT_OVERFLOW,
    UNAVAILABLE;

    public String tooltipKey() {
      return "gui.mmcece.network_port.transfer." + (this == NONE ? "configure" : name().toLowerCase(Locale.ROOT));
    }
  }

  public record Target(NetworkPortResource resource, int amount) {
    public Target {
      Objects.requireNonNull(resource, "resource");
      if (amount < 1) {
        throw new IllegalArgumentException("A network recipe target must have a positive amount");
      }
    }
  }

  private final Error error;
  private final List<Target> targets;

  private NetworkPortRecipePlan(Error error, List<Target> targets) {
    this.error = error;
    this.targets = targets;
  }

  public Error error() {
    return error;
  }

  public List<Target> targets() {
    return targets;
  }

  public static NetworkPortRecipePlan unavailable() {
    return new NetworkPortRecipePlan(Error.UNAVAILABLE, List.of());
  }

  public static NetworkPortRecipePlan forRecipe(MachineRecipe recipe, ResourceType kind) {
    if (kind != ResourceType.ITEM && kind != ResourceType.FLUID && kind != ResourceType.CHEMICAL) {
      return new NetworkPortRecipePlan(Error.UNSUPPORTED, List.of());
    }
    String resource = kind.id().getPath();
    boolean perTick = kind == ResourceType.FLUID && !recipe.fluidPerTickInputs().isEmpty()
        || kind == ResourceType.CHEMICAL && !recipe.chemicals().perTick().isEmpty();
    for (var group : recipe.ingredientArrays().inputs()) {
      if (group.resource().equals(resource)
          || group.recipeModifiers().stream().anyMatch(
              rule -> rule.io() == RecipeModifier.Io.INPUT
                  && (rule.target().serialized().equals(resource)
                      || perTick && rule.target() == RecipeModifier.Target.DURATION))) {
        return new NetworkPortRecipePlan(Error.UNSUPPORTED, List.of());
      }
    }
    if (recipe.triggeredResources().rows().stream()
        .anyMatch(row -> row.io() == RecipeModifier.Io.INPUT && row.resource().equals(resource))) {
      return new NetworkPortRecipePlan(Error.UNSUPPORTED, List.of());
    }
    var builder = new Builder();
    if (kind == ResourceType.ITEM) {
      if (!recipe.itemPayments().isEmpty()
          || recipe.scriptOptions().itemCallbacks().stream().anyMatch(row -> row.io() == RecipeModifier.Io.INPUT)) {
        return new NetworkPortRecipePlan(Error.UNSUPPORTED, List.of());
      }
      for (var row : recipe.inputs()) {
        if (row.minimumAmount() != row.amount()) {
          return new NetworkPortRecipePlan(Error.UNSUPPORTED, List.of());
        }
        // The declared prototype also satisfies an ingredient that accepts any
        // components.
        builder.add(new NetworkPortResource.Item(row.ingredientStack()), row.amount(), 1, row.selector(), row.chance());
      }
    } else if (kind == ResourceType.FLUID) {
      builder.fluids(recipe.fluidInputRequirements(), 1, recipe.selectors().fluidInput());
      builder.fluids(recipe.fluidPerTickInputs(), recipe.duration(), recipe.selectors().fluidPerTick());
    } else {
      builder.chemicals(recipe.chemicals().inputs(), 1);
      builder.chemicals(recipe.chemicals().perTick(), recipe.duration());
    }
    return builder.finish();
  }

  private static final class Builder {
    private final LinkedHashMap<NetworkPortResource, Integer> amounts = new LinkedHashMap<>();
    private String selector;
    private Error error = Error.NONE;

    void fluids(List<FluidRequirement> rows, int ticks, String phaseSelector) {
      for (var row : rows) {
        var stack = row.stack();
        add(new NetworkPortResource.Fluid(stack), stack.getAmount(), ticks, row.selector(phaseSelector), row.chance());
      }
    }

    void chemicals(List<ChemicalRequirement> rows, int ticks) {
      for (var row : rows) {
        add(new NetworkPortResource.Chemical(row.chemical()), row.amount(), ticks, row.selector(), row.chance());
      }
    }

    void add(NetworkPortResource key, long amount, int ticks, String route, double chance) {
      if (error != Error.NONE) {
        return;
      }
      if (chance != 1 || selector != null && !Objects.equals(selector, route)) {
        error = Error.UNSUPPORTED;
        return;
      }
      selector = route;
      int existing = amounts.getOrDefault(key, 0);
      if (amount > (Integer.MAX_VALUE - existing) / ticks) {
        error = Error.AMOUNT_OVERFLOW;
      } else if (existing == 0 && amounts.size() == 9) {
        error = Error.TOO_MANY;
      } else {
        amounts.put(key, existing + (int) (amount * ticks));
      }
    }

    NetworkPortRecipePlan finish() {
      if (error != Error.NONE || amounts.isEmpty()) {
        return new NetworkPortRecipePlan(error == Error.NONE ? Error.NO_INPUT : error, List.of());
      }
      var targets = amounts.entrySet().stream().map(entry -> new Target(entry.getKey(), entry.getValue())).toList();
      return new NetworkPortRecipePlan(Error.NONE, targets);
    }
  }
}
