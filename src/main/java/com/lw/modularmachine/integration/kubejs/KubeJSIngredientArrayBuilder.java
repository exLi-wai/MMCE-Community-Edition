package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.machine.recipe.RecipeScriptCallbacks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.tags.TagKey;

/** Internal script facade for homogeneous input alternatives. */
@ApiStatus.Internal
public final class KubeJSIngredientArrayBuilder {
  private static final AtomicLong IDS = new AtomicLong();

  private enum SnapshotUse {
    INPUT,
    OUTPUT
  }

  private KubeJSIngredientArrayBuilder() {
  }

  public static IngredientArrayPrimer newBuilder() {
    return new IngredientArrayPrimer();
  }

  public static final class IngredientArrayPrimer {
    private final Thread owner = Thread.currentThread();
    private final List<Choice> alternatives = new ArrayList<>();
    private final Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers = new LinkedHashMap<>();
    private final Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers = new LinkedHashMap<>();
    private Choice last;

    private IngredientArrayPrimer() {
    }

    private void requireMutable() {
      if (Thread.currentThread() != owner) {
        throw new IllegalStateException("Ingredient arrays must be edited on their declaration thread");
      }
    }

    public IngredientArrayPrimer addIngredient(ItemStack stack) {
      Objects.requireNonNull(stack, "item ingredient");
      return addItem(stack, stack.getCount());
    }

    public IngredientArrayPrimer addIngredient(String ingredient) {
      if (ingredient != null && ingredient.startsWith("#")) {
        return addIngredient(tagIngredient(ingredient));
      }
      var id = KubeJSRecipeArguments.identifier(ingredient, "item ingredient");
      return addIngredient(
          new ItemStack(
              BuiltInRegistries.ITEM.getOptional(id)
                  .orElseThrow(() -> new IllegalArgumentException("Unknown item ingredient " + id))));
    }

    public IngredientArrayPrimer addIngredient(Ingredient ingredient) {
      return addIngredient(ingredient, 1L);
    }

    public IngredientArrayPrimer addIngredient(Ingredient ingredient, Number amount) {
      return addIngredient(ingredient, KubeJSRecipeArguments.integer(amount, "ingredient amount", 1, Long.MAX_VALUE));
    }

    public IngredientArrayPrimer addIngredient(Ingredient ingredient, String amount) {
      return addIngredient(ingredient, KubeJSRecipeArguments.integer(amount, "ingredient amount", 1, Long.MAX_VALUE));
    }

    private IngredientArrayPrimer addIngredient(Ingredient ingredient, long amount) {
      requireMutable();
      var choice = new Choice("item", null, null, null, amount);
      choice.ingredient = Objects.requireNonNull(ingredient, "ingredient");
      return append(choice);
    }

    public IngredientArrayPrimer addIngredient(FluidStack stack) {
      Objects.requireNonNull(stack, "fluid ingredient");
      requireMutable();
      if (stack.isEmpty()) {
        throw new IllegalArgumentException("An ingredient array cannot contain an empty fluid");
      }
      return append(new Choice("fluid", null, stack.copyWithAmount(1), null, stack.getAmount()));
    }

    public IngredientArrayPrimer addIngredient(ChemicalStackData stack) {
      Objects.requireNonNull(stack, "chemical ingredient");
      return addChemical(stack.chemical(), stack.amount());
    }

    public IngredientArrayPrimer addIngredient(ChemicalRequirement stack) {
      Objects.requireNonNull(stack, "chemical ingredient");
      return addChemical(stack.chemical(), stack.amount()).setChance(stack.chance());
    }

    public IngredientArrayPrimer addIngredient(String chemical, Number amount) {
      if (chemical != null && chemical.startsWith("#")) {
        return addIngredient(tagIngredient(chemical), amount);
      }
      return addChemical(
          KubeJSRecipeArguments.identifier(chemical, "chemical ingredient"),
          KubeJSRecipeArguments.integer(amount, "chemical ingredient amount", 1, Long.MAX_VALUE));
    }

    public IngredientArrayPrimer addIngredient(String chemical, String amount) {
      if (chemical != null && chemical.startsWith("#")) {
        return addIngredient(tagIngredient(chemical), amount);
      }
      return addChemical(
          KubeJSRecipeArguments.identifier(chemical, "chemical ingredient"),
          KubeJSRecipeArguments.integer(amount, "chemical ingredient amount", 1, Long.MAX_VALUE));
    }

    public IngredientArrayPrimer addIngredients(List<?> ingredients) {
      requireMutable();
      for (var ingredient : ingredients) {
        appendIngredient(ingredient);
      }
      return this;
    }

    private void appendIngredient(Object ingredient) {
      if (ingredient instanceof ItemStack item) {
        addIngredient(item);
      } else if (ingredient instanceof FluidStack fluid) {
        addIngredient(fluid);
      } else if (ingredient instanceof ChemicalStackData chemical) {
        addIngredient(chemical);
      } else if (ingredient instanceof ChemicalRequirement chemical) {
        addIngredient(chemical);
      } else if (ingredient instanceof Ingredient item) {
        addIngredient(item);
      } else if (ingredient instanceof String item) {
        addIngredient(item);
      } else {
        throw new IllegalArgumentException("Ingredient arrays accept Item.of, Fluid.of or a chemical declaration");
      }
    }

    public IngredientArrayPrimer addChancedIngredient(ItemStack stack, Number chance) {
      return addIngredient(stack).setChance(chance);
    }

    public IngredientArrayPrimer addChancedIngredient(Ingredient ingredient, Number chance) {
      return addIngredient(ingredient).setChance(chance);
    }

    public IngredientArrayPrimer addChancedIngredient(FluidStack stack, Number chance) {
      return addIngredient(stack).setChance(chance);
    }

    public IngredientArrayPrimer addChancedIngredient(ChemicalStackData stack, Number chance) {
      return addIngredient(stack).setChance(chance);
    }

    public IngredientArrayPrimer addChancedIngredient(ChemicalRequirement stack, Number chance) {
      return addIngredient(stack).setChance(chance);
    }

    public IngredientArrayPrimer addChancedIngredient(String chemical, Number amount, Number chance) {
      return addIngredient(chemical, amount).setChance(chance);
    }

    public IngredientArrayPrimer addChancedIngredient(String chemical, String amount, Number chance) {
      return addIngredient(chemical, amount).setChance(chance);
    }

    public IngredientArrayPrimer setChance(Number chance) {
      var choice = requireLast();
      double probability = Objects.requireNonNull(chance, "chance").doubleValue();
      if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
        throw new IllegalArgumentException("Input ingredient probability must be within 0..1");
      }
      choice.chance = probability;
      return this;
    }

    public IngredientArrayPrimer setMinMaxAmount(Number min, Number max) {
      return setMinMaxAmount(
          KubeJSRecipeArguments.integer(min, "minimum ingredient amount", 0, Long.MAX_VALUE),
          KubeJSRecipeArguments.integer(max, "maximum ingredient amount", 1, Long.MAX_VALUE));
    }

    public IngredientArrayPrimer setMinMaxAmount(String min, String max) {
      return setMinMaxAmount(
          KubeJSRecipeArguments.integer(min, "minimum ingredient amount", 0, Long.MAX_VALUE),
          KubeJSRecipeArguments.integer(max, "maximum ingredient amount", 1, Long.MAX_VALUE));
    }

    private IngredientArrayPrimer setMinMaxAmount(long min, long max) {
      var choice = requireItem();
      if (min > max) {
        throw new IllegalArgumentException("Minimum ingredient amount cannot exceed the maximum");
      }
      choice.minAmount = min;
      choice.amount = max;
      return this;
    }

    public IngredientArrayPrimer setChecker(String id) {
      requireItem().checker = KubeJSRecipeArguments.identifier(id, "ingredient checker");
      return this;
    }

    public IngredientArrayPrimer setChecker(RecipeItemCallbacks.Checker handler) {
      var choice = requireItem();
      choice.checker = nextId("checker");
      checkers.put(choice.checker, Objects.requireNonNull(handler, "checker"));
      return this;
    }

    public IngredientArrayPrimer addItemModifier(String id) {
      requireItem().modifiers.add(KubeJSRecipeArguments.identifier(id, "ingredient modifier"));
      return this;
    }

    public IngredientArrayPrimer addItemModifier(RecipeItemCallbacks.Modifier handler) {
      var choice = requireItem();
      var id = nextId("modifier");
      choice.modifiers.add(id);
      modifiers.put(id, Objects.requireNonNull(handler, "modifier"));
      return this;
    }

    /** Old build was optional and returned the same primer. */
    public IngredientArrayPrimer build() {
      requireMutable();
      if (alternatives.isEmpty()) {
        throw new IllegalStateException("Add an ingredient before building its alternatives");
      }
      return this;
    }

    public IngredientArrayData.Group snapshot() {
      var server = ServerLifecycleHooks.getCurrentServer();
      return snapshot(server == null ? null : server.registryAccess());
    }

    IngredientArrayData.Group snapshot(HolderLookup.Provider registries) {
      return snapshot(registries, SnapshotUse.INPUT);
    }

    IngredientArrayData.Group snapshotForOutput(HolderLookup.Provider registries) {
      return snapshot(registries, SnapshotUse.OUTPUT);
    }

    private IngredientArrayData.Group snapshot(HolderLookup.Provider registries, SnapshotUse use) {
      requireMutable();
      var resolved = new ArrayList<IngredientArrayData.Choice>();
      for (var choice : alternatives) {
        choice.appendSnapshots(resolved, registries, use);
      }
      return new IngredientArrayData.Group(resolved, 1, false, "");
    }

    void captureCallbacks() {
      checkers.forEach(RecipeScriptCallbacks::checker);
      modifiers.forEach(RecipeScriptCallbacks::modifier);
    }

    void captureAdapterCallbacks() {
      checkers.forEach(RecipeScriptCallbacks::adapterChecker);
      modifiers.forEach(RecipeScriptCallbacks::adapterModifier);
    }

    private IngredientArrayPrimer addItem(ItemStack stack, long amount) {
      requireMutable();
      if (stack.isEmpty() || amount < 1) {
        throw new IllegalArgumentException("An ingredient array cannot contain an empty item");
      }
      return append(new Choice("item", stack.copyWithCount(1), null, null, amount));
    }

    private IngredientArrayPrimer addChemical(ResourceLocation chemical, long amount) {
      requireMutable();
      if (amount < 1) {
        throw new IllegalArgumentException("Chemical ingredient quantities must be positive");
      }
      return append(new Choice("chemical", null, null, chemical, amount));
    }

    private IngredientArrayPrimer append(Choice choice) {
      if (alternatives.size() >= IngredientArrayData.MAX_ALTERNATIVES
          || last != null && !last.resource.equals(choice.resource)) {
        throw new IllegalArgumentException("Ingredient groups must be bounded and use one resource kind");
      }
      alternatives.add(choice);
      last = choice;
      return this;
    }

    private Choice requireLast() {
      requireMutable();
      if (last == null) {
        throw new IllegalStateException("Add an ingredient before setting its properties");
      }
      return last;
    }

    private Choice requireItem() {
      var choice = requireLast();
      if (!choice.resource.equals("item")) {
        throw new IllegalStateException("Item callbacks require an item ingredient");
      }
      return choice;
    }

    private static ResourceLocation nextId(String kind) {
      return ResourceLocation
          .fromNamespaceAndPath("kubejs", "mmcece/ingredient_array/" + IDS.incrementAndGet() + "/" + kind);
    }
  }

  private static final class Choice {
    private final String resource;
    private final ItemStack item;
    private final FluidStack fluid;
    private final ResourceLocation chemical;
    private long amount;
    private long minAmount;
    private Ingredient ingredient;
    private double chance = 1;
    private ResourceLocation checker;
    private final List<ResourceLocation> modifiers = new ArrayList<>();

    private Choice(String resource, ItemStack item, FluidStack fluid, ResourceLocation chemical, long amount) {
      this.resource = resource;
      this.item = item;
      this.fluid = fluid;
      this.chemical = chemical;
      this.amount = amount;
      this.minAmount = amount;
    }

    private void appendSnapshots(List<IngredientArrayData.Choice> resolved, HolderLookup.Provider registries, SnapshotUse use) {
      if (ingredient == null) {
        resolved.add(snapshot(Optional.ofNullable(item), "exact"));
        return;
      }
      var candidates = itemCandidates(ingredient, registries);
      for (var prototype : use == SnapshotUse.OUTPUT ? candidates.subList(0, 1) : candidates) {
        resolved.add(snapshot(Optional.of(prototype), use == SnapshotUse.OUTPUT ? "exact" : "item"));
      }
    }

    private IngredientArrayData.Choice snapshot(Optional<ItemStack> prototype, String match) {
      return new IngredientArrayData.Choice(
          resource,
          prototype,
          Optional.ofNullable(fluid),
          Optional.ofNullable(chemical),
          amount,
          chance,
          Optional.ofNullable(checker),
          modifiers,
          minAmount,
          amount,
          match);
    }
  }

  static List<ItemStack> itemCandidates(Ingredient ingredient, HolderLookup.Provider registries) {
    var items = new TreeMap<ResourceLocation, ItemStack>();
    collectItems(ingredient, registries, items, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    if (items.isEmpty() || items.size() > IngredientArrayData.MAX_ALTERNATIVES) {
      throw new IllegalArgumentException("Item ingredients need 1..256 resolved native item candidates");
    }

    return List.copyOf(items.values());
  }

  static Ingredient tagIngredient(String tag) {
    if (tag == null || !tag.startsWith("#")) {
      throw new IllegalArgumentException("An item tag must start with #");
    }
    return Ingredient
        .of(TagKey.create(Registries.ITEM, KubeJSRecipeArguments.identifier(tag.substring(1), "item tag")));
  }

  private static void collectItems(Ingredient ingredient, HolderLookup.Provider registries, Map<ResourceLocation, ItemStack> items, Set<Ingredient> active, int depth) {
    if (depth > 16 || !active.add(ingredient)) {
      throw new IllegalArgumentException("Item ingredients cannot be cyclic or deeply nested");
    }
    try {
      if (ingredient.getCustomIngredient() instanceof CompoundIngredient compound) {
        for (var child : compound.children()) {
          collectItems(child, registries, items, active, depth + 1);
        }
        return;
      }
      if (ingredient.isCustom()) {
        throw new IllegalArgumentException("Custom item predicates require their own native recipe resource");
      }
      for (var value : ingredient.getValues()) {
        if (value instanceof Ingredient.ItemValue item) {
          var stack = item.item();
          if (!stack.isEmpty()) {
            items.putIfAbsent(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.copyWithCount(1));
          }
        } else if (value instanceof Ingredient.TagValue tag) {
          if (registries == null) {
            throw new IllegalStateException("Tag ingredients require an active recipe registry context");
          }
          registries.lookupOrThrow(Registries.ITEM).getOrThrow(tag.tag()).stream().forEach(
              holder -> items
                  .putIfAbsent(BuiltInRegistries.ITEM.getKey(holder.value()), new ItemStack(holder.value())));
        } else {
          throw new IllegalArgumentException("Unknown native ingredient value");
        }
      }
    } finally {
      active.remove(ingredient);
    }
  }
}
