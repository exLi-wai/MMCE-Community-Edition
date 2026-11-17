package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Immutable alternatives; optional groups add rules only when selected. */
public record IngredientArrayData(List<Group> inputs, List<Group> randomOutputs) {
  public static final int MAX_GROUPS = 64;
  public static final int MAX_ALTERNATIVES = 256;
  public static final IngredientArrayData NONE = new IngredientArrayData(List.of(), List.of());
  public static final Codec<IngredientArrayData> CODEC = Group.CODEC.listOf()
      .xmap(IngredientArrayData::new, IngredientArrayData::inputs);
  public static final MapCodec<IngredientArrayData> MAP_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          Group.CODEC.listOf().optionalFieldOf("ingredient_arrays", List.of()).forGetter(IngredientArrayData::inputs),
          Group.CODEC.listOf().optionalFieldOf("random_item_outputs", List.of())
              .forGetter(IngredientArrayData::randomOutputs))
          .apply(instance, IngredientArrayData::new));

  public IngredientArrayData(List<Group> inputs) {
    this(inputs, List.of());
  }

  public IngredientArrayData {
    inputs = List.copyOf(inputs);
    randomOutputs = List.copyOf(randomOutputs);
    if (inputs.size() > MAX_GROUPS || randomOutputs.size() > MAX_GROUPS) {
      throw new IllegalArgumentException("Too many ingredient array input groups");
    }
    randomOutputs.forEach(IngredientArrayData::validateRandomOutput);
  }

  /** Validates a group before it is added to a random-output declaration. */
  public static void validateRandomOutput(Group group) {
    if (!group.resource().equals("item")
        || group.optional()
        || group.alternatives().stream().mapToDouble(Choice::chance).sum() <= 0
        || group.alternatives().stream()
            .anyMatch(choice -> choice.checker().isPresent() || !choice.match().equals("exact"))) {
      throw new IllegalArgumentException(
          "Random item outputs require item groups without input checkers or catalyst rules");
    }
  }

  public boolean isEmpty() {
    return inputs.isEmpty();
  }

  public record Group(
      List<Choice> alternatives,
      double chance,
      boolean parallelUnaffected,
      String selector,
      boolean optional,
      List<String> tooltips,
      List<RecipeModifier> recipeModifiers) {
    public static final Codec<Group> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Choice.CODEC.listOf().fieldOf("alternatives").forGetter(Group::alternatives),
            Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(Group::chance),
            Codec.BOOL.optionalFieldOf("parallel_unaffected", false).forGetter(Group::parallelUnaffected),
            Codec.STRING.optionalFieldOf("selector", "").forGetter(Group::selector),
            Codec.BOOL.optionalFieldOf("optional", false).forGetter(Group::optional),
            Codec.STRING.listOf().optionalFieldOf("tooltips", List.of()).forGetter(Group::tooltips),
            RecipeModifier.CODEC.listOf().optionalFieldOf("recipe_modifiers", List.of())
                .forGetter(Group::recipeModifiers))
            .apply(instance, Group::new));

    public Group(List<Choice> alternatives, double chance, boolean parallelUnaffected, String selector) {
      this(alternatives, chance, parallelUnaffected, selector, false, List.of(), List.of());
    }

    public Group {
      alternatives = List.copyOf(alternatives);
      tooltips = new RecipeScriptOptions(tooltips, List.of()).tooltips();
      recipeModifiers = List.copyOf(recipeModifiers);
      if (alternatives.isEmpty() || alternatives.size() > MAX_ALTERNATIVES || !validChance(chance)) {
        throw new IllegalArgumentException("Ingredient groups need bounded alternatives and a probability within 0..1");
      }
      String resource = alternatives.getFirst().resource();
      if (alternatives.stream().anyMatch(choice -> !choice.resource().equals(resource))) {
        throw new IllegalArgumentException("An ingredient group cannot mix item, fluid and chemical candidates");
      }
      if (parallelUnaffected && resource.equals("chemical")) {
        throw new IllegalArgumentException("Chemical ingredient groups do not support fixed parallel quantities");
      }
      if (recipeModifiers.size() > 256 || !optional && (!tooltips.isEmpty() || !recipeModifiers.isEmpty())) {
        throw new IllegalArgumentException("Conditional rules and tooltips require an optional catalyst group");
      }
      selector = ResourceSelector.require(selector);
    }

    public String resource() {
      return alternatives.getFirst().resource();
    }
  }

  public record Choice(
      String resource,
      Optional<ItemStack> item,
      Optional<FluidStack> fluid,
      Optional<ResourceLocation> chemical,
      long amount,
      double chance,
      Optional<ResourceLocation> checker,
      List<ResourceLocation> modifiers,
      long minAmount,
      long maxAmount,
      String match) {
    public static final Codec<Choice> CODEC = RecordCodecBuilder
        .create(
            instance -> instance
                .group(
                    Codec.STRING.fieldOf("resource").forGetter(Choice::resource),
                    Codec.lazyInitialized(() -> ItemStack.SINGLE_ITEM_CODEC).optionalFieldOf("item")
                        .forGetter(Choice::item),
                    Codec.lazyInitialized(() -> FluidStack.CODEC).optionalFieldOf("fluid").forGetter(Choice::fluid),
                    ResourceLocation.CODEC.optionalFieldOf("chemical").forGetter(Choice::chemical),
                    Codec.LONG.fieldOf("amount").forGetter(Choice::amount),
                    Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(Choice::chance),
                    ResourceLocation.CODEC.optionalFieldOf("checker").forGetter(Choice::checker),
                    ResourceLocation.CODEC.listOf().optionalFieldOf("modifiers", List.of())
                        .forGetter(Choice::modifiers),
                    Codec.LONG.optionalFieldOf("min_amount", 0L).forGetter(Choice::minAmount),
                    Codec.LONG.optionalFieldOf("max_amount", 0L).forGetter(Choice::maxAmount),
                    Codec.STRING.optionalFieldOf("match", "exact").forGetter(Choice::match))
                .apply(instance, Choice::new));

    public Choice(String resource, Optional<ItemStack> item, Optional<FluidStack> fluid, Optional<ResourceLocation> chemical, long amount, double chance, Optional<ResourceLocation> checker, List<ResourceLocation> modifiers) {
      this(resource, item, fluid, chemical, amount, chance, checker, modifiers, 0, 0, "exact");
    }

    public Choice(String resource, Optional<ItemStack> item, Optional<FluidStack> fluid, Optional<ResourceLocation> chemical, long amount, double chance, Optional<ResourceLocation> checker, List<ResourceLocation> modifiers, long minAmount, long maxAmount) {
      this(resource, item, fluid, chemical, amount, chance, checker, modifiers, minAmount, maxAmount, "exact");
    }

    public Choice {
      Objects.requireNonNull(resource, "resource");
      item = Objects.requireNonNull(item, "item").map(ItemStack::copy);
      fluid = Objects.requireNonNull(fluid, "fluid").map(FluidStack::copy);
      Objects.requireNonNull(chemical, "chemical");
      Objects.requireNonNull(checker, "checker");
      Objects.requireNonNull(match, "match");
      modifiers = List.copyOf(modifiers);
      if (minAmount == 0 && maxAmount == 0) {
        minAmount = amount;
        maxAmount = amount;
      }
      if (!validPrototype(resource, item, fluid, chemical, amount)
          || !validAmountRange(resource, amount, minAmount, maxAmount)
          || !(match.equals("exact") || resource.equals("item") && match.equals("item"))
          || !validChance(chance)
          || !validCallbacks(resource, checker, modifiers)) {
        throw new IllegalArgumentException("Invalid ingredient array candidate");
      }
    }

    private static boolean validPrototype(String resource, Optional<ItemStack> item, Optional<FluidStack> fluid, Optional<ResourceLocation> chemical, long amount) {
      return switch (resource) {
        case "item" -> item.isPresent()
            && !item.orElseThrow().isEmpty()
            && item.orElseThrow().getCount() == 1
            && fluid.isEmpty()
            && chemical.isEmpty();
        case "fluid" -> fluid.isPresent()
            && !fluid.orElseThrow().isEmpty()
            && item.isEmpty()
            && chemical.isEmpty()
            && amount <= Integer.MAX_VALUE;
        case "chemical" -> chemical.isPresent() && item.isEmpty() && fluid.isEmpty();
        default -> false;
      };
    }

    private static boolean validAmountRange(String resource, long amount, long minimum, long maximum) {
      return amount >= 1
          && minimum >= 0
          && maximum >= minimum
          && amount == maximum
          && (resource.equals("item") || minimum == amount && maximum == amount);
    }

    private static boolean validCallbacks(String resource, Optional<ResourceLocation> checker, List<ResourceLocation> modifiers) {
      return modifiers.size() <= RecipeScriptOptions.MAX_ROW_MODIFIERS
          && modifiers.stream().distinct().count() == modifiers.size()
          && (resource.equals("item") || checker.isEmpty() && modifiers.isEmpty());
    }

    @Override
    public Optional<ItemStack> item() {
      return item.map(ItemStack::copy);
    }

    @Override
    public Optional<FluidStack> fluid() {
      return fluid.map(FluidStack::copy);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Choice value)) {
        return false;
      }
      return resource.equals(value.resource)
          && amount == value.amount
          && minAmount == value.minAmount
          && maxAmount == value.maxAmount
          && chance == value.chance
          && chemical.equals(value.chemical)
          && checker.equals(value.checker)
          && modifiers.equals(value.modifiers)
          && match.equals(value.match)
          && item.isPresent() == value.item.isPresent()
          && fluid.isPresent() == value.fluid.isPresent()
          && (item.isEmpty() || ItemStack.matches(item.orElseThrow(), value.item.orElseThrow()))
          && (fluid.isEmpty() || FluidStack.matches(fluid.orElseThrow(), value.fluid.orElseThrow()));
    }

    @Override
    public int hashCode() {
      var stack = item.orElse(ItemStack.EMPTY);
      var liquid = fluid.orElse(FluidStack.EMPTY);
      return Objects.hash(
          resource,
          amount,
          minAmount,
          maxAmount,
          chance,
          chemical,
          checker,
          modifiers,
          match,
          stack.getItem(),
          stack.getComponents(),
          stack.getCount(),
          liquid.getFluid(),
          liquid.getComponents(),
          liquid.getAmount());
    }
  }

  private static boolean validChance(double chance) {
    return Double.isFinite(chance) && chance >= 0 && chance <= 1;
  }
}
