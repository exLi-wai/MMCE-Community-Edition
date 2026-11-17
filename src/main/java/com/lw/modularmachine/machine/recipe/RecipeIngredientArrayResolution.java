package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.machine.PortKind;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Replaces selected groups with exact rows while retaining the recipe identity.
 */
@ApiStatus.Internal
public final class RecipeIngredientArrayResolution {
  private static final Set<String> GROUP_FIELDS = Set
      .of("alternatives", "chance", "parallel_unaffected", "selector", "optional", "tooltips", "recipe_modifiers");
  private static final Set<String> CHOICE_FIELDS = Set.of(
      "resource",
      "item",
      "fluid",
      "chemical",
      "amount",
      "chance",
      "checker",
      "modifiers",
      "min_amount",
      "max_amount",
      "match");
  private static final Set<String> RESOURCE_KINDS = Set.of("item", "fluid", "chemical");

  public record Selection(int choice, Optional<ItemStack> actualItem) {
    public Selection {
      actualItem = actualItem.map(ItemStack::copy);
    }

    @Override
    public Optional<ItemStack> actualItem() {
      return actualItem.map(ItemStack::copy);
    }
  }

  private RecipeIngredientArrayResolution() {
  }

  public static List<RecipeModifier> selectedRules(IngredientArrayData data, int[] selected) {
    if (data.inputs().size() != selected.length) {
      throw new IllegalArgumentException("Ingredient selection differs from the declared group count");
    }
    var rules = new ArrayList<RecipeModifier>();
    for (int index = 0; index < selected.length; index++) {
      var group = data.inputs().get(index);
      if (selected[index] < -1
          || selected[index] >= group.alternatives().size()
          || selected[index] == -1 && !group.optional()) {
        throw new IllegalArgumentException("Ingredient selection refers to a missing alternative");
      }
      if (selected[index] >= 0) {
        rules.addAll(group.recipeModifiers());
      }
    }

    return List.copyOf(rules);
  }

  public static JsonObject resolve(MachineRecipe source, List<Selection> selected, HolderLookup.Provider registries) {
    var groups = source.ingredientArrays().inputs();
    if (selected.size() != groups.size()) {
      throw new IllegalArgumentException("Ingredient selection differs from the declared group count");
    }
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    var result = MachineRecipe.CODEC.codec().encodeStart(ops, source).getOrThrow().getAsJsonObject();
    result.remove("ingredient_arrays");
    var callbacks = new ArrayList<>(source.scriptOptions().itemCallbacks());
    for (int groupIndex = 0; groupIndex < selected.size(); groupIndex++) {
      var group = groups.get(groupIndex);
      var selection = selected.get(groupIndex);
      int choiceIndex = selection.choice();
      if (choiceIndex == -1 && group.optional()) {
        continue;
      }
      if (choiceIndex < 0 || choiceIndex >= group.alternatives().size()) {
        throw new IllegalArgumentException("Ingredient selection refers to a missing alternative");
      }
      var choice = group.alternatives().get(choiceIndex);
      double chance = group.chance() * choice.chance();
      switch (choice.resource()) {
        case "item" -> appendItemInput(result, group, choice, selection, chance, ops).ifPresent(callbacks::add);
        case "fluid" -> appendFluidInput(result, group, choice, chance, ops);
        case "chemical" -> appendChemicalInput(result, group, choice, chance);
        default -> throw new IllegalArgumentException("Unknown ingredient group resource");
      }
    }
    if (!callbacks.isEmpty()) {
      result.add(
          "item_callbacks",
          RecipeScriptOptions.ItemCallbacks.CODEC.listOf().encodeStart(ops, callbacks).getOrThrow());
    }
    MachineRecipeJson.validate(result);

    return result;
  }

  private static Optional<RecipeScriptOptions.ItemCallbacks> appendItemInput(JsonObject result, IngredientArrayData.Group group, IngredientArrayData.Choice choice, Selection selection, double chance, DynamicOps<JsonElement> ops) {
    var prototype = itemPrototype(choice, selection);
    var row = new JsonObject();
    row.add("stack", ItemStack.SINGLE_ITEM_CODEC.encodeStart(ops, prototype).getOrThrow());
    row.addProperty("amount", choice.amount());
    if (choice.minAmount() != choice.maxAmount()) {
      row.addProperty("min_amount", choice.minAmount());
      row.addProperty("max_amount", choice.maxAmount());
    }
    row.addProperty("chance", chance);
    row.addProperty("selector", group.selector());
    row.addProperty("parallel_unaffected", group.parallelUnaffected());
    var inputs = array(result, "inputs");
    int inputIndex = inputs.size();
    inputs.add(row);
    if (choice.checker().isEmpty() && choice.modifiers().isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        new RecipeScriptOptions.ItemCallbacks(
            RecipeModifier.Io.INPUT,
            inputIndex,
            choice.checker(),
            choice.modifiers()));
  }

  private static ItemStack itemPrototype(IngredientArrayData.Choice choice, Selection selection) {
    var prototype = choice.item().orElseThrow();
    if (choice.match().equals("item")) {
      var actual = selection.actualItem().orElseThrow(
          () -> new IllegalArgumentException("Item-only alternatives require a captured physical prototype"));
      if (!actual.is(prototype.getItem())) {
        throw new IllegalArgumentException("Captured ingredient differs from its declared item");
      }
      prototype = actual.copyWithCount(1);
    }

    return prototype;
  }

  private static void appendFluidInput(JsonObject result, IngredientArrayData.Group group, IngredientArrayData.Choice choice, double chance, DynamicOps<JsonElement> ops) {
    var stack = choice.fluid().orElseThrow().copyWithAmount(Math.toIntExact(choice.amount()));
    var row = FluidStack.CODEC.encodeStart(ops, stack).getOrThrow().getAsJsonObject();
    row.addProperty("chance", chance);
    row.addProperty("parallel_unaffected", group.parallelUnaffected());
    var inputs = array(result, "fluid_inputs");
    var selectors = result.has("selectors") ? result.getAsJsonObject("selectors") : new JsonObject();
    String existing = selectors.has("fluid_input") ? selectors.get("fluid_input").getAsString() : "";
    if (!inputs.isEmpty() && !existing.equals(group.selector())) {
      throw new IllegalArgumentException("Fluid ingredient groups and ordinary inputs must share their phase selector");
    }
    inputs.add(row);
    selectors.addProperty("fluid_input", group.selector());
    result.add("selectors", selectors);
  }

  private static void appendChemicalInput(JsonObject result, IngredientArrayData.Group group, IngredientArrayData.Choice choice, double chance) {
    var chemicals = result.has("chemicals") ? result.getAsJsonObject("chemicals") : new JsonObject();
    var row = new JsonObject();
    row.addProperty("chemical", choice.chemical().orElseThrow().toString());
    row.addProperty("amount", choice.amount());
    row.addProperty("chance", chance);
    row.addProperty("selector", group.selector());
    array(chemicals, "inputs").add(row);
    result.add("chemicals", chemicals);
  }

  private static JsonArray array(JsonObject parent, String field) {
    if (!parent.has(field)) {
      parent.add(field, new JsonArray());
    }
    return parent.getAsJsonArray(field);
  }

  private record PhysicalSlot(Object storage, int slot) {
  }

  /**
   * Captures the exact complete input slices, including zero-chance candidates.
   */
  public static List<RecipeItemCallbackService.InputItem> checkerSlices(MachineRecipe recipe, RecipeExecutionTicket ticket, int instances) {
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid ingredient checker instance count");
    }
    var result = new ArrayList<RecipeItemCallbackService.InputItem>();
    var reserved = new HashMap<PhysicalSlot, Long>();
    for (int row = 0; row < recipe.inputs().size(); row++) {
      if (recipe.itemPayments().isTool(row)) {
        continue;
      }
      var demand = recipe.inputs().get(row);
      long remaining = demand.parallelUnaffected() ? demand.amount() : Math.multiplyExact(demand.amount(), instances);
      var key = ItemResourceKey.of(demand.prototype(ticket.level().registryAccess()), ticket.level().registryAccess())
          .withSelector(demand.selector());
      if (demand.ignoresComponents()) {
        key = key.ignoringComponents();
      }
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.ITEM_INPUT || !port.selector().equals(demand.selector())) {
          continue;
        }
        var stacks = port.items();
        for (int slot = 0; slot < stacks.size() && remaining > 0; slot++) {
          var actual = stacks.get(slot);
          if (!key.matches(actual)) {
            continue;
          }
          var physical = new PhysicalSlot(port.physicalIdentity(), slot);
          long used = reserved.getOrDefault(physical, 0L);
          long count = Math.min(remaining, Math.max(0, port.itemAmount(slot) - used));
          if (count > 0) {
            reserved.put(physical, Math.addExact(used, count));
            result.add(new RecipeItemCallbackService.InputItem(row, actual, count));
            remaining -= count;
          }
        }
        if (remaining == 0) {
          break;
        }
      }
      if (remaining > 0) {
        return List.of();
      }
    }

    var tools = RecipeItemPayments
        .toolSlices(ticket, recipe, instances, ticket.owner() instanceof MachineControllerBlockEntity);
    if (tools.isEmpty()) {
      return List.of();
    }
    result.addAll(tools.orElseThrow());
    return List.copyOf(result);
  }

  /** Structural checks do not need item/fluid registry codecs. */
  public static void validate(JsonElement declaration) {
    if (declaration == null
        || !declaration.isJsonArray()
        || declaration.getAsJsonArray().size() > IngredientArrayData.MAX_GROUPS) {
      throw new IllegalArgumentException("Ingredient arrays require a bounded group array");
    }
    String fluidSelector = null;
    for (var element : declaration.getAsJsonArray()) {
      var group = requireGroup(element);
      String selector = group.has("selector") ? group.get("selector").getAsString() : "";
      ResourceSelector.require(selector);
      probability(group, "chance");
      boolean optional = group.has("optional") && group.get("optional").getAsBoolean();
      validateGroupFlags(group);
      validateCatalystRules(group, optional);
      String kind = validateAlternatives(group.getAsJsonArray("alternatives"));
      validateFixedParallel(group, kind);
      if (kind.equals("fluid")) {
        if (fluidSelector != null && !fluidSelector.equals(selector)) {
          throw new IllegalArgumentException("Fluid ingredient arrays share their input phase selector");
        }
        fluidSelector = selector;
      }
    }
  }

  private static JsonObject requireGroup(JsonElement element) {
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("An ingredient group must be an object");
    }
    var group = element.getAsJsonObject();
    if (!GROUP_FIELDS.containsAll(group.keySet())
        || !group.has("alternatives")
        || !group.get("alternatives").isJsonArray()
        || group.getAsJsonArray("alternatives").isEmpty()
        || group.getAsJsonArray("alternatives").size() > IngredientArrayData.MAX_ALTERNATIVES) {
      throw new IllegalArgumentException("An ingredient group needs bounded alternatives");
    }

    return group;
  }

  private static void validateGroupFlags(JsonObject group) {
    if (group.has("optional")
        && (!group.get("optional").isJsonPrimitive() || !group.get("optional").getAsJsonPrimitive().isBoolean())
        || group.has("parallel_unaffected")
            && (!group.get("parallel_unaffected").isJsonPrimitive()
                || !group.get("parallel_unaffected").getAsJsonPrimitive().isBoolean())) {
      throw new IllegalArgumentException("Ingredient group flags require booleans");
    }
  }

  private static void validateCatalystRules(JsonObject group, boolean optional) {
    var rules = group.has("recipe_modifiers") ? RecipeModifiers
        .parse(group.get("recipe_modifiers"), "$.ingredient_arrays.recipe_modifiers") : List.<RecipeModifier>of();
    var tooltips = group.has("tooltips") ? Codec.STRING.listOf().parse(JsonOps.INSTANCE, group.get("tooltips"))
        .getOrThrow() : List.<String>of();
    new RecipeScriptOptions(tooltips, List.of());
    if (rules.size() > 256 || !optional && (!rules.isEmpty() || !tooltips.isEmpty())) {
      throw new IllegalArgumentException("Conditional rules and tooltips require an optional catalyst group");
    }
  }

  private static String validateAlternatives(JsonArray alternatives) {
    String kind = null;
    for (var element : alternatives) {
      var candidate = requireAlternative(element);
      String resource = candidate.get("resource").getAsString();
      if (!RESOURCE_KINDS.contains(resource) || kind != null && !kind.equals(resource)) {
        throw new IllegalArgumentException("An ingredient group must use one supported resource kind");
      }
      kind = resource;
      validatePrototype(candidate, resource);
      validateAmount(candidate, resource);
      probability(candidate, "chance");
    }

    return kind;
  }

  private static JsonObject requireAlternative(JsonElement element) {
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("An ingredient alternative must be an object");
    }
    var candidate = element.getAsJsonObject();
    if (!CHOICE_FIELDS.containsAll(candidate.keySet()) || !candidate.has("resource") || !candidate.has("amount")) {
      throw new IllegalArgumentException("Invalid ingredient alternative fields");
    }

    return candidate;
  }

  private static void validatePrototype(JsonObject candidate, String resource) {
    if (!candidate.has(resource)
        || !resource.equals("item") && candidate.has("item")
        || !resource.equals("fluid") && candidate.has("fluid")
        || !resource.equals("chemical") && candidate.has("chemical")
        || !resource.equals("item") && (candidate.has("checker") || candidate.has("modifiers"))) {
      throw new IllegalArgumentException("Ingredient prototype and callbacks differ from its resource kind");
    }
  }

  private static void validateAmount(JsonObject candidate, String resource) {
    long amount = candidate.get("amount").getAsBigDecimal().longValueExact();
    String match = candidate.has("match") ? candidate.get("match").getAsString() : "exact";
    if (!(match.equals("exact") || resource.equals("item") && match.equals("item"))) {
      throw new IllegalArgumentException("Ingredient matching requires an exact prototype or an item-only input");
    }
    if (amount < 1 || resource.equals("fluid") && amount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Ingredient quantities must be positive and fit their resource range");
    }
    long minimum = candidate.has("min_amount") ? candidate.get("min_amount").getAsBigDecimal()
        .longValueExact() : amount;
    long maximum = candidate.has("max_amount") ? candidate.get("max_amount").getAsBigDecimal()
        .longValueExact() : amount;
    if (minimum < 0 || maximum < minimum || maximum != amount || !resource.equals("item") && minimum != maximum) {
      throw new IllegalArgumentException("Ingredient quantity ranges require positive ordered item amounts");
    }
  }

  private static void validateFixedParallel(JsonObject group, String kind) {
    if (kind.equals("chemical")
        && group.has("parallel_unaffected")
        && group.get("parallel_unaffected").getAsBoolean()) {
      throw new IllegalArgumentException("Chemical ingredient arrays do not support fixed parallel quantities");
    }
  }

  public static void validateRandomOutputs(JsonElement declaration) {
    validate(declaration);
    for (var element : declaration.getAsJsonArray()) {
      var group = element.getAsJsonObject();
      if (group.has("optional") && group.get("optional").getAsBoolean()
          || group.has("tooltips") && !group.getAsJsonArray("tooltips").isEmpty()
          || group.has("recipe_modifiers") && !group.getAsJsonArray("recipe_modifiers").isEmpty()) {
        throw new IllegalArgumentException("Random item outputs cannot declare optional catalyst rules");
      }
      double weights = 0;
      for (var alternative : group.getAsJsonArray("alternatives")) {
        var choice = alternative.getAsJsonObject();
        if (!choice.get("resource").getAsString().equals("item")
            || choice.has("checker")
            || choice.has("match") && !choice.get("match").getAsString().equals("exact")) {
          throw new IllegalArgumentException("Random item outputs require item alternatives without input checkers");
        }
        weights += choice.has("chance") ? choice.get("chance").getAsDouble() : 1;
      }
      if (weights <= 0) {
        throw new IllegalArgumentException("Random item outputs need a positive total weight");
      }
    }
  }

  private static void probability(JsonObject object, String field) {
    if (object.has(field)) {
      double value = object.get(field).getAsDouble();
      if (!Double.isFinite(value) || value < 0 || value > 1) {
        throw new IllegalArgumentException("Ingredient probabilities must be within 0..1");
      }
    }
  }
}
