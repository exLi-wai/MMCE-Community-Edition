package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.ItemAmount;
import com.lw.modularmachine.machine.recipe.RecipeDeclaredObservers;
import com.lw.modularmachine.machine.recipe.RecipePreparationHandlers;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.integration.kubejs.KubeJSRecipeRows.ItemRow;
import com.lw.modularmachine.integration.kubejs.KubeJSRecipeRows.FluidRow;
import com.lw.modularmachine.integration.kubejs.KubeJSRecipeRows.ChemicalRow;
import com.lw.modularmachine.machine.recipe.RecipeScriptCallbacks;
import com.google.gson.JsonElement;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import com.lw.modularmachine.machine.recipe.resource.EnergyRecipeData;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.ItemRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import dev.latvian.mods.kubejs.plugin.builtin.wrapper.ItemWrapper;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Internal server-recipe facade; only build attaches a native declaration. */
@ApiStatus.Internal
public final class KubeJSRecipeBuilder {
  private KubeJSRecipeBuilder() {
  }

  public static RecipeItemCallbacks.ItemRequirement itemRequirement(ItemStack stack, Number amount) {
    return RecipeItemCallbacks.requirement(stack, amount);
  }

  public static RecipeItemCallbacks.ItemRequirement itemRequirement(ItemStack stack, String amount) {
    return RecipeItemCallbacks.requirement(stack, amount);
  }

  public static ChemicalRequirement chemical(String chemical, Number amount) {
    return chemical(chemical, KubeJSRecipeArguments.integer(amount, "chemical amount", 1, Long.MAX_VALUE));
  }

  public static ChemicalRequirement chemical(String chemical, String amount) {
    return chemical(chemical, KubeJSRecipeArguments.integer(amount, "chemical amount", 1, Long.MAX_VALUE));
  }

  private static ChemicalRequirement chemical(String chemical, long amount) {
    return new ChemicalRequirement(KubeJSRecipeArguments.identifier(chemical, "chemical"), amount, "");
  }

  public static RecipeBuilder newBuilder(Context context, RecipesKubeEvent event, String recipeName, String machineName, Number processingTickTime) {
    return newBuilder(context, event, recipeName, machineName, processingTickTime, 0);
  }

  public static RecipeBuilder newBuilder(Context context, RecipesKubeEvent event, String recipeName, String machineName, Number processingTickTime, Number sortingPriority) {
    requireServerScript(context);
    Objects.requireNonNull(event, "recipe event");
    var id = KubeJSRecipeArguments.identifier(recipeName, "recipe");
    if (recipeName.indexOf(':') < 0) {
      id = ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, id.getPath());
    }
    return new RecipeBuilder(
        event,
        id,
        KubeJSRecipeArguments.identifier(machineName, "machine"),
        (int) KubeJSRecipeArguments.integer(processingTickTime, "processing tick time", 1, Integer.MAX_VALUE),
        (int) KubeJSRecipeArguments.integer(sortingPriority, "sorting priority", Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  private static void requireServerScript(Context context) {
    if (!(context instanceof KubeJSContext kubeContext) || kubeContext.getType() != ScriptType.SERVER) {
      throw new IllegalStateException("Machine recipe builders require a server recipe script");
    }
  }

  public static final class RecipeBuilder extends KubeJSResourceRecipeBuilder<RecipeBuilder> {
    private enum LastRequirement {
      NONE,
      ITEM,
      FUEL,
      ENERGY,
      ENERGY_OUTPUT,
      FLUID_INPUT,
      FLUID_OUTPUT,
      FLUID_PER_TICK,
      FLUID_PER_TICK_OUTPUT,
      INGREDIENT_ARRAY,
      RANDOM_OUTPUT,
      CHEMICAL
    }

    private final RecipesKubeEvent event;
    private final ResourceLocation id, machine;
    private final int duration, priority;
    private final Thread owner = Thread.currentThread();
    private final List<ItemRow> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private final List<ItemPaymentData.Fuel> fuelInputs = new ArrayList<>();
    private final List<FluidRow> fluidInputs = new ArrayList<>(), fluidOutputs = new ArrayList<>(),
        fluidPerTickInputs = new ArrayList<>(), fluidPerTickOutputs = new ArrayList<>();
    private final List<ChemicalRow> chemicalInputs = new ArrayList<>(), chemicalOutputs = new ArrayList<>(),
        chemicalPerTick = new ArrayList<>(), chemicalPerTickOutputs = new ArrayList<>();
    private final List<SmartNumberRequirement> interfaceInputs = new ArrayList<>();
    private final List<RecipeModifier> modifiers = new ArrayList<>();
    private final List<String> tooltips = new ArrayList<>();
    private final List<RecipeDeclaredObservers.Handler> lifecycleHandlers = new ArrayList<>();
    private final List<RecipePreparationHandlers.Handler> preparationHandlers = new ArrayList<>();
    private final List<IngredientArrayData.Group> ingredientArrays = new ArrayList<>();
    private final List<IngredientArrayData.Group> randomItemOutputs = new ArrayList<>();
    private final List<KubeJSIngredientArrayBuilder.GroupCallbacks> ingredientArrayCallbacks = new ArrayList<>();
    private long energyPerTick;
    private long energyOutputPerTick;
    private int energyRequirements;
    private int energyOutputRequirements;
    private String energySelector = "";
    private String energyOutputSelector = "";
    private String threadName = "";
    private int maxThreads;
    private String fluidInputSelector = "", fluidOutputSelector = "", fluidPerTickSelector = "";
    private boolean fluidInputTagged, fluidOutputTagged, fluidPerTickTagged, energyTagged;
    private boolean energyOutputTagged;
    private long randomSeed;
    private boolean loadJei = true;
    private Boolean parallelized;
    private RecipeFailureAction failureAction;
    private LastRequirement last = LastRequirement.NONE;
    private ItemRow lastItem;
    private ChemicalRow lastChemical;
    private FluidRow lastFluid;
    private boolean built;

    private RecipeBuilder(RecipesKubeEvent event, ResourceLocation id, ResourceLocation machine, int duration, int priority) {
      this.event = event;
      this.id = id;
      this.machine = machine;
      this.duration = duration;
      this.priority = priority;
    }

    @Override
    void requireMutable() {
      if (built || Thread.currentThread() != owner) {
        throw new IllegalStateException("Machine recipe builder is built or used outside its recipe thread");
      }
    }

    @Override
    HolderLookup.Provider resourceRegistries() {
      return event.registries.access();
    }

    @Override
    RecipeBuilder clearLastResourceRow() {
      last = LastRequirement.NONE;
      lastItem = null;
      lastChemical = null;
      lastFluid = null;
      return this;
    }

    public RecipeBuilder addItemInput(Context context, Object ingredient) {
      return addScriptItem(context, inputs, ingredient, null);
    }

    public RecipeBuilder addItemInput(Context context, Object ingredient, Object amount) {
      return addScriptItem(context, inputs, ingredient, scriptItemAmount(amount));
    }

    private static long scriptItemAmount(Object amount) {
      if (amount instanceof Number number) {
        return KubeJSRecipeArguments.integer(number, "item amount", 1, Long.MAX_VALUE);
      }
      if (amount instanceof CharSequence text) {
        return KubeJSRecipeArguments.integer(text.toString(), "item amount", 1, Long.MAX_VALUE);
      }
      throw new IllegalArgumentException("Expected a numeric item amount or an exact decimal string");
    }

    // Rhino gives ItemStack and Ingredient equal conversion priority for script
    // strings.
    private RecipeBuilder addScriptItem(Context context, List<ItemRow> rows, Object value, Long amount) {
      if (value instanceof CharSequence text && text.toString().startsWith("#")) {
        value = KubeJSIngredientArrayBuilder.tagIngredient(text.toString());
      }
      if (value instanceof Ingredient ingredient) {
        long count = amount == null ? 1L : amount;
        if (rows == inputs) {
          return addIngredientArrayInput(
              KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient, Long.valueOf(count)));
        }
        var prototype = KubeJSIngredientArrayBuilder.itemCandidates(ingredient, event.registries.access()).getFirst();
        return addItem(outputs, prototype, count);
      }
      var stack = value instanceof ItemStack item ? item : ItemWrapper.wrap(context, value);
      return addItem(rows, stack, amount == null ? stack.getCount() : amount);
    }

    public RecipeBuilder addIngredientArrayInput(KubeJSIngredientArrayBuilder.IngredientArrayPrimer ingredients) {
      requireMutable();
      Objects.requireNonNull(ingredients, "ingredient array");
      if (ingredientArrays.size() >= IngredientArrayData.MAX_GROUPS) {
        throw new IllegalArgumentException("Too many ingredient array inputs");
      }
      var group = ingredients.snapshot(event.registries.access());
      ingredientArrays.add(group);
      ingredientArrayCallbacks.add(ingredients.callbacksFor(group));
      last = LastRequirement.INGREDIENT_ARRAY;
      lastItem = null;
      lastChemical = null;
      lastFluid = null;

      return this;
    }

    public RecipeBuilder addInput(ItemStack stack) {
      return addItem(inputs, stack, stack.getCount());
    }

    public RecipeBuilder addRandomItemOutput(KubeJSIngredientArrayBuilder.IngredientArrayPrimer ingredients) {
      requireMutable();
      var group = Objects.requireNonNull(ingredients, "random output ingredients")
          .snapshotForOutput(event.registries.access());
      IngredientArrayData.validateRandomOutput(group);
      if (randomItemOutputs.size() >= IngredientArrayData.MAX_GROUPS) {
        throw new IllegalArgumentException("Too many random item output groups");
      }
      randomItemOutputs.add(group);
      ingredientArrayCallbacks.add(ingredients.callbacksFor(group));
      last = LastRequirement.RANDOM_OUTPUT;
      lastItem = null;

      return this;
    }

    public RecipeBuilder addCatalystInput(ItemStack ingredient, List<String> hints, List<RecipeModifier> rules) {
      return addCatalystInput(KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient), hints, rules);
    }

    public RecipeBuilder addCatalystInput(FluidStack ingredient, List<String> hints, List<RecipeModifier> rules) {
      return addCatalystInput(KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient), hints, rules);
    }

    public RecipeBuilder addCatalystInput(ChemicalRequirement ingredient, List<String> hints, List<RecipeModifier> rules) {
      return addCatalystInput(KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient), hints, rules);
    }

    public RecipeBuilder addCatalystInput(KubeJSIngredientArrayBuilder.IngredientArrayPrimer ingredients, List<String> hints, List<RecipeModifier> rules) {
      addIngredientArrayInput(ingredients);
      var group = ingredientArrays.getLast();
      ingredientArrays.set(
          ingredientArrays.size() - 1,
          new IngredientArrayData.Group(
              group.alternatives(),
              group.chance(),
              !group.resource().equals("chemical"),
              group.selector(),
              true,
              hints,
              rules));

      return this;
    }

    public RecipeBuilder addInput(FluidStack stack) {
      return addFluidInput(stack);
    }

    public RecipeBuilder addOutput(ItemStack stack) {
      return addItem(outputs, stack, stack.getCount());
    }

    public RecipeBuilder addOutput(FluidStack stack) {
      return addFluidOutput(stack);
    }

    public RecipeBuilder addInputs(List<?> stacks) {
      return addResources(stacks, false);
    }

    public RecipeBuilder addOutputs(List<?> stacks) {
      return addResources(stacks, true);
    }

    private RecipeBuilder addResources(List<?> stacks, boolean output) {
      requireMutable();
      for (Object value : Objects.requireNonNull(stacks, "resources")) {
        if (value instanceof ItemStack item) {
          if (output) {
            addItem(outputs, item, item.getCount());
          } else {
            addItem(inputs, item, item.getCount());
          }
        } else if (value instanceof FluidStack fluid) {
          if (output) {
            addFluidOutput(fluid);
          } else {
            addFluidInput(fluid);
          }
        } else {
          throw new IllegalArgumentException("Mixed resources require Item.of(...) or Fluid.of(...) values");
        }
      }
      last = LastRequirement.NONE;
      lastItem = null;
      lastFluid = null;
      return this;
    }

    public RecipeBuilder addFuelItemInput(Number requiredTotalBurnTime) {
      return addFuelItemInput(
          KubeJSRecipeArguments.integer(requiredTotalBurnTime, "fuel burn time", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder addFuelItemInput(String requiredTotalBurnTime) {
      return addFuelItemInput(
          KubeJSRecipeArguments.integer(requiredTotalBurnTime, "fuel burn time", 1, Long.MAX_VALUE));
    }

    private RecipeBuilder addFuelItemInput(long requiredTotalBurnTime) {
      requireMutable();
      if (fuelInputs.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many fuel requirements");
      }
      fuelInputs.add(new ItemPaymentData.Fuel(requiredTotalBurnTime, 1, false, ""));
      last = LastRequirement.FUEL;
      lastItem = null;
      lastFluid = null;
      lastChemical = null;
      return this;
    }

    public RecipeBuilder consumeDurability(Number durability) {
      requireMutable();
      if (last != LastRequirement.ITEM || lastItem == null || !inputs.contains(lastItem)) {
        throw new IllegalStateException("consumeDurability requires the last item input");
      }
      int amount = (int) KubeJSRecipeArguments.integer(durability, "tool durability", 0, Integer.MAX_VALUE);
      if (amount > 0 && !lastItem.prototype.isDamageableItem()) {
        throw new IllegalArgumentException("consumeDurability requires a damageable item");
      }
      lastItem.consumeDurability = amount;
      return this;
    }

    public RecipeBuilder addItemInputIgnoringComponents(ItemStack stack) {
      return addItemInputIgnoringComponents(stack, stack == null ? null : stack.getCount());
    }

    public RecipeBuilder addItemInputIgnoringComponents(ItemStack stack, Number amount) {
      return addItemIgnoringComponents(stack, KubeJSRecipeArguments.integer(amount, "item amount", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder addItemInputIgnoringComponents(ItemStack stack, String amount) {
      return addItemIgnoringComponents(stack, KubeJSRecipeArguments.integer(amount, "item amount", 1, Long.MAX_VALUE));
    }

    private RecipeBuilder addItemIgnoringComponents(ItemStack stack, long amount) {
      requireMutable();
      if (!inputs.isEmpty()) {
        throw new IllegalArgumentException("Item-only matching requires a single item input row");
      }
      addItem(inputs, stack, amount);
      lastItem.match = "item";
      return this;
    }

    public RecipeBuilder addItemOutput(Context context, Object ingredient) {
      return addScriptItem(context, outputs, ingredient, null);
    }

    public RecipeBuilder addItemOutput(Context context, Object ingredient, Object amount) {
      return addScriptItem(context, outputs, ingredient, scriptItemAmount(amount));
    }

    public RecipeBuilder addItemInputs(List<ItemStack> stacks) {
      return addItems(inputs, stacks);
    }

    public RecipeBuilder addItemOutputs(List<ItemStack> stacks) {
      return addItems(outputs, stacks);
    }

    private RecipeBuilder addItems(List<ItemRow> rows, List<ItemStack> stacks) {
      requireMutable();
      Objects.requireNonNull(stacks, "item requirements");
      if (stacks.size() > MachineRecipeJson.MAX_DEMANDS - rows.size()) {
        throw new IllegalArgumentException("Too many machine recipe item requirements");
      }
      for (var stack : stacks) {
        addItem(rows, stack, stack == null ? 0 : stack.getCount());
      }
      last = LastRequirement.NONE;
      lastItem = null;
      return this;
    }

    private RecipeBuilder addItem(List<ItemRow> rows, ItemStack stack, long amount) {
      requireMutable();
      if (rows == inputs && !inputs.isEmpty() && inputs.getFirst().match.equals("item")) {
        throw new IllegalArgumentException("Item-only matching requires a single item input row");
      }
      if (stack == null || stack.isEmpty() || amount <= 0) {
        throw new IllegalArgumentException("Machine recipe item requirement must not be empty");
      }
      if (rows.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many machine recipe item requirements");
      }
      var row = new ItemRow(ItemStackSnapshots.detached(stack.copyWithCount(1), event.registries.access()), amount);
      rows.add(row);
      last = LastRequirement.ITEM;
      lastItem = row;
      return this;
    }

    public RecipeBuilder addEnergyPerTickInput(Number amount) {
      return addEnergy(KubeJSRecipeArguments.integer(amount, "FE/t", 0, Long.MAX_VALUE));
    }

    public RecipeBuilder addEnergyPerTickInput(String amount) {
      return addEnergy(KubeJSRecipeArguments.integer(amount, "FE/t", 0, Long.MAX_VALUE));
    }

    private RecipeBuilder addEnergy(long amount) {
      requireMutable();
      if (!energySelector.isEmpty()) {
        throw new IllegalStateException("Additional energy requirements cannot change a tagged energy cohort");
      }
      energyPerTick = Math.addExact(energyPerTick, amount);
      energyRequirements = Math.incrementExact(energyRequirements);
      last = LastRequirement.ENERGY;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addEnergyPerTickOutput(Number amount) {
      return addEnergyOutput(KubeJSRecipeArguments.integer(amount, "output FE/t", 0, Long.MAX_VALUE));
    }

    public RecipeBuilder addEnergyPerTickOutput(String amount) {
      return addEnergyOutput(KubeJSRecipeArguments.integer(amount, "output FE/t", 0, Long.MAX_VALUE));
    }

    private RecipeBuilder addEnergyOutput(long amount) {
      requireMutable();
      if (!energyOutputSelector.isEmpty()) {
        throw new IllegalStateException("Additional energy requirements cannot change a tagged energy cohort");
      }
      energyOutputPerTick = Math.addExact(energyOutputPerTick, amount);
      energyOutputRequirements = Math.incrementExact(energyOutputRequirements);
      last = LastRequirement.ENERGY_OUTPUT;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addFluidInput(FluidStack fluid) {
      requireMutable();
      if (fluidInputs.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many machine recipe fluid inputs");
      }
      lastFluid = new FluidRow(detachedFluid(fluid));
      fluidInputs.add(lastFluid);
      last = LastRequirement.FLUID_INPUT;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addFluidOutput(FluidStack fluid) {
      requireMutable();
      if (fluidOutputs.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many machine recipe fluid outputs");
      }
      lastFluid = new FluidRow(detachedFluid(fluid));
      fluidOutputs.add(lastFluid);
      last = LastRequirement.FLUID_OUTPUT;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addFluidPerTickInput(FluidStack fluid) {
      return addTickFluid(fluidPerTickInputs, fluid, false);
    }

    public RecipeBuilder addFluidPerTickOutput(FluidStack fluid) {
      return addTickFluid(fluidPerTickOutputs, fluid, true);
    }

    public RecipeBuilder addFluidPerTickInputs(List<FluidStack> fluids) {
      return addTickFluids(fluids, false);
    }

    public RecipeBuilder addFluidPerTickOutputs(List<FluidStack> fluids) {
      return addTickFluids(fluids, true);
    }

    private RecipeBuilder addTickFluid(List<FluidRow> rows, FluidStack fluid, boolean output) {
      requireMutable();
      if (rows.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many fluid-per-tick requirements");
      }
      lastFluid = new FluidRow(detachedFluid(fluid));
      lastFluid.selector = output ? "" : fluidPerTickSelector;
      rows.add(lastFluid);
      last = output ? LastRequirement.FLUID_PER_TICK_OUTPUT : LastRequirement.FLUID_PER_TICK;
      lastItem = null;
      lastChemical = null;

      return this;
    }

    private RecipeBuilder addTickFluids(List<FluidStack> fluids, boolean output) {
      requireMutable();
      Objects.requireNonNull(fluids, "fluid-per-tick requirements");
      for (var fluid : fluids) {
        if (output) {
          addFluidPerTickOutput(fluid);
        } else {
          addFluidPerTickInput(fluid);
        }
      }
      last = LastRequirement.NONE;
      lastFluid = null;

      return this;
    }

    public RecipeBuilder addFluidInputs(List<FluidStack> fluids) {
      return addFluids(fluidInputs, fluids, false);
    }

    public RecipeBuilder addFluidOutputs(List<FluidStack> fluids) {
      return addFluids(fluidOutputs, fluids, true);
    }

    private RecipeBuilder addFluids(List<FluidRow> rows, List<FluidStack> fluids, boolean output) {
      requireMutable();
      Objects.requireNonNull(fluids, "fluid requirements");
      if (fluids.size() > MachineRecipeJson.MAX_DEMANDS - rows.size()) {
        throw new IllegalArgumentException("Too many machine recipe fluid requirements");
      }
      for (var fluid : fluids) {
        if (output) {
          addFluidOutput(fluid);
        } else {
          addFluidInput(fluid);
        }
      }
      last = LastRequirement.NONE;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addChemicalInput(String chemical, Number amount) {
      return addChemical(chemicalInputs, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalInput(String chemical, String amount) {
      return addChemical(chemicalInputs, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalOutput(String chemical, Number amount) {
      return addChemical(chemicalOutputs, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalOutput(String chemical, String amount) {
      return addChemical(chemicalOutputs, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalPerTickInput(String chemical, Number amount) {
      return addChemical(chemicalPerTick, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalPerTickInput(String chemical, String amount) {
      return addChemical(chemicalPerTick, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalPerTickOutput(String chemical, Number amount) {
      return addChemical(chemicalPerTickOutputs, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addChemicalPerTickOutput(String chemical, String amount) {
      return addChemical(chemicalPerTickOutputs, chemical, chemicalAmount(amount));
    }

    public RecipeBuilder addGasInput(String chemical, Number amount) {
      return addChemicalInput(chemical, amount);
    }

    public RecipeBuilder addGasInput(String chemical, String amount) {
      return addChemicalInput(chemical, amount);
    }

    public RecipeBuilder addGasOutput(String chemical, Number amount) {
      return addChemicalOutput(chemical, amount);
    }

    public RecipeBuilder addGasOutput(String chemical, String amount) {
      return addChemicalOutput(chemical, amount);
    }

    public RecipeBuilder addGasPerTickInput(String chemical, Number amount) {
      return addChemicalPerTickInput(chemical, amount);
    }

    public RecipeBuilder addGasPerTickInput(String chemical, String amount) {
      return addChemicalPerTickInput(chemical, amount);
    }

    public RecipeBuilder addGasPerTickOutput(String chemical, Number amount) {
      return addChemicalPerTickOutput(chemical, amount);
    }

    public RecipeBuilder addGasPerTickOutput(String chemical, String amount) {
      return addChemicalPerTickOutput(chemical, amount);
    }

    public RecipeBuilder addGasInput(ChemicalRequirement chemical) {
      return addChemical(chemicalInputs, chemical);
    }

    public RecipeBuilder addGasOutput(ChemicalRequirement chemical) {
      return addChemical(chemicalOutputs, chemical);
    }

    public RecipeBuilder addGasPerTickInput(ChemicalRequirement chemical) {
      return addChemical(chemicalPerTick, chemical);
    }

    public RecipeBuilder addGasPerTickOutput(ChemicalRequirement chemical) {
      return addChemical(chemicalPerTickOutputs, chemical);
    }

    public RecipeBuilder addGasInputs(List<ChemicalRequirement> chemicals) {
      return addChemicals(chemicalInputs, chemicals);
    }

    public RecipeBuilder addGasOutputs(List<ChemicalRequirement> chemicals) {
      return addChemicals(chemicalOutputs, chemicals);
    }

    public RecipeBuilder addGasPerTickInputs(List<ChemicalRequirement> chemicals) {
      return addChemicals(chemicalPerTick, chemicals);
    }

    public RecipeBuilder addGasPerTickOutputs(List<ChemicalRequirement> chemicals) {
      return addChemicals(chemicalPerTickOutputs, chemicals);
    }

    public RecipeBuilder addChemicalInput(ChemicalRequirement chemical) {
      return addGasInput(chemical);
    }

    public RecipeBuilder addChemicalOutput(ChemicalRequirement chemical) {
      return addGasOutput(chemical);
    }

    public RecipeBuilder addChemicalPerTickInput(ChemicalRequirement chemical) {
      return addGasPerTickInput(chemical);
    }

    public RecipeBuilder addChemicalPerTickOutput(ChemicalRequirement chemical) {
      return addGasPerTickOutput(chemical);
    }

    public RecipeBuilder addChemicalInputs(List<ChemicalRequirement> chemicals) {
      return addGasInputs(chemicals);
    }

    public RecipeBuilder addChemicalOutputs(List<ChemicalRequirement> chemicals) {
      return addGasOutputs(chemicals);
    }

    public RecipeBuilder addChemicalPerTickInputs(List<ChemicalRequirement> chemicals) {
      return addGasPerTickInputs(chemicals);
    }

    public RecipeBuilder addChemicalPerTickOutputs(List<ChemicalRequirement> chemicals) {
      return addGasPerTickOutputs(chemicals);
    }

    private RecipeBuilder addChemical(List<ChemicalRow> rows, ChemicalRequirement chemical) {
      Objects.requireNonNull(chemical, "chemical requirement");
      addChemical(rows, chemical.chemical().toString(), chemical.amount());
      lastChemical.selector = chemical.selector();
      lastChemical.chance = chemical.chance();
      lastChemical.ignoreOutputCheck = chemical.ignoreOutputCheck();

      return this;
    }

    private RecipeBuilder addChemicals(List<ChemicalRow> rows, List<ChemicalRequirement> chemicals) {
      requireMutable();
      Objects.requireNonNull(chemicals, "chemical requirements");
      if (chemicals.size() > ChemicalRecipeData.MAX_REQUIREMENTS - rows.size()) {
        throw new IllegalArgumentException("Too many chemical requirements");
      }
      for (var chemical : chemicals) {
        addChemical(rows, chemical);
      }
      last = LastRequirement.NONE;
      lastChemical = null;

      return this;
    }

    private RecipeBuilder addChemical(List<ChemicalRow> rows, String chemical, long amount) {
      requireMutable();
      if (rows.size() >= ChemicalRecipeData.MAX_REQUIREMENTS) {
        throw new IllegalArgumentException("Too many machine recipe chemical requirements");
      }
      var row = new ChemicalRow(KubeJSRecipeArguments.identifier(chemical, "chemical"), amount);
      rows.add(row);
      last = LastRequirement.CHEMICAL;
      lastChemical = row;
      lastItem = null;
      return this;
    }

    private static long chemicalAmount(Number value) {
      return KubeJSRecipeArguments.integer(value, "chemical amount", 1, Long.MAX_VALUE);
    }

    private static long chemicalAmount(String value) {
      return KubeJSRecipeArguments.integer(value, "chemical amount", 1, Long.MAX_VALUE);
    }

    private FluidStack detachedFluid(FluidStack fluid) {
      if (fluid == null || fluid.isEmpty()) {
        throw new IllegalArgumentException("Machine recipe fluid requirement must not be empty");
      }
      var copy = FluidStack.CODEC.parse(
          event.registries.access().createSerializationContext(JsonOps.INSTANCE),
          encode(FluidStack.CODEC, fluid).deepCopy()).getOrThrow();
      if (!FluidStack.matches(fluid, copy)) {
        throw new IllegalArgumentException("Lossy fluid components cannot be used in a recipe requirement");
      }
      return copy;
    }

    public RecipeBuilder setParallelized(boolean enabled) {
      requireMutable();
      parallelized = enabled;
      return this;
    }

    public RecipeBuilder addSmartInterfaceDataInput(String type, Number minimum, Number maximum) {
      requireMutable();
      if (interfaceInputs.size() >= SmartInterfaceType.MAX_TYPES) {
        throw new IllegalArgumentException("Too many machine recipe smart interface requirements");
      }
      interfaceInputs.add(
          new SmartNumberRequirement(
              type,
              Objects.requireNonNull(minimum, "minimum").doubleValue(),
              Objects.requireNonNull(maximum, "maximum").doubleValue()));
      last = LastRequirement.NONE;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addSmartInterfaceDataInput(String type, Number value) {
      return addSmartInterfaceDataInput(type, value, value);
    }

    public RecipeBuilder addModifier(RecipeModifier modifier) {
      requireMutable();
      if (modifiers.size() >= RecipeModifiers.MAX_RULES) {
        throw new IllegalArgumentException("Too many machine recipe modifiers");
      }
      modifiers.add(Objects.requireNonNull(modifier, "modifier"));
      return this;
    }

    public RecipeBuilder addModifiers(List<RecipeModifier> rules) {
      requireMutable();
      Objects.requireNonNull(rules, "modifiers");
      if (rules.size() > RecipeModifiers.MAX_RULES - modifiers.size()) {
        throw new IllegalArgumentException("Too many machine recipe modifiers");
      }
      for (var rule : rules) {
        addModifier(rule);
      }
      return this;
    }

    public RecipeBuilder setRandomSeed(Number seed) {
      requireMutable();
      randomSeed = KubeJSRecipeArguments.integer(seed, "random seed", 0, Long.MAX_VALUE);
      return this;
    }

    public RecipeBuilder setRandomSeed(String seed) {
      requireMutable();
      randomSeed = KubeJSRecipeArguments.integer(seed, "random seed", 0, Long.MAX_VALUE);
      return this;
    }

    public RecipeBuilder setTriggerTime(Number ticks) {
      requireMutable();
      int tick = (int) KubeJSRecipeArguments.integer(ticks, "trigger time", 0, duration);
      if (tick > 0
          && (last == LastRequirement.ITEM && lastItem != null && lastItem.ignoreOutputCheck
              || (last == LastRequirement.FLUID_INPUT || last == LastRequirement.FLUID_OUTPUT)
                  && lastFluid != null
                  && lastFluid.ignoreOutputCheck
              || last == LastRequirement.CHEMICAL && lastChemical != null && lastChemical.ignoreOutputCheck)) {
        throw new IllegalStateException("Triggered outputs require complete output space");
      }
      switch (last) {
        case ITEM -> lastItem.triggerTime = tick;
        case FLUID_INPUT, FLUID_OUTPUT -> lastFluid.triggerTime = tick;
        case CHEMICAL -> {
          if (chemicalPerTick.contains(lastChemical) || chemicalPerTickOutputs.contains(lastChemical)) {
            throw new IllegalStateException(
                "Trigger time applies to startup/final resource IO; per-tick rows already execute every tick");
          }
          lastChemical.triggerTime = tick;
        }
        default ->
          throw new IllegalStateException("Trigger time requires a last item, fluid or chemical startup/final row");
      }
      return this;
    }

    public RecipeBuilder setTriggerRepeatable(boolean repeatable) {
      requireMutable();
      switch (last) {
        case ITEM -> lastItem.triggerRepeatable = repeatable;
        case FLUID_INPUT, FLUID_OUTPUT -> lastFluid.triggerRepeatable = repeatable;
        case CHEMICAL -> {
          if (chemicalPerTick.contains(lastChemical) || chemicalPerTickOutputs.contains(lastChemical)) {
            throw new IllegalStateException("Trigger repeatability requires a startup/final resource row");
          }
          lastChemical.triggerRepeatable = repeatable;
        }
        default -> throw new IllegalStateException(
            "Trigger repeatability requires a last item, fluid or chemical startup/final row");
      }
      return this;
    }

    public RecipeBuilder setChance(Number chance) {
      requireMutable();
      Objects.requireNonNull(chance, "chance");
      double value = chance.doubleValue();
      if (!Double.isFinite(value) || value < 0 || value > 1) {
        throw new IllegalArgumentException("Chance must be finite and within 0..1");
      }
      if (last == LastRequirement.ITEM && lastItem != null) {
        lastItem.chance = value;
      } else if (last == LastRequirement.FUEL) {
        var fuel = fuelInputs.getLast();
        fuelInputs.set(
            fuelInputs.size() - 1,
            new ItemPaymentData.Fuel(fuel.burnTime(), value, fuel.parallelUnaffected(), fuel.selector()));
      } else if (last == LastRequirement.RANDOM_OUTPUT) {
        KubeJSIngredientArrayBuilder.setLastGroupChance(randomItemOutputs, value);
      } else if (last == LastRequirement.INGREDIENT_ARRAY) {
        KubeJSIngredientArrayBuilder.setLastGroupChance(ingredientArrays, value);
      } else if (last == LastRequirement.CHEMICAL && lastChemical != null) {
        lastChemical.chance = value;
      } else if ((last == LastRequirement.FLUID_INPUT
          || last == LastRequirement.FLUID_OUTPUT
          || last == LastRequirement.FLUID_PER_TICK
          || last == LastRequirement.FLUID_PER_TICK_OUTPUT) && lastFluid != null) {
        lastFluid.chance = value;
      } else {
        throw new IllegalStateException("Chance requires the last item, chemical or fluid requirement");
      }
      return this;
    }

    public RecipeBuilder setParallelizeUnaffected(boolean unaffected) {
      requireMutable();
      if (last == LastRequirement.ITEM && lastItem != null) {
        lastItem.parallelUnaffected = unaffected;
      } else if (last == LastRequirement.FUEL) {
        var fuel = fuelInputs.getLast();
        fuelInputs.set(
            fuelInputs.size() - 1,
            new ItemPaymentData.Fuel(fuel.burnTime(), fuel.chance(), unaffected, fuel.selector()));
      } else if (last == LastRequirement.RANDOM_OUTPUT) {
        KubeJSIngredientArrayBuilder.setLastGroupParallelUnaffected(randomItemOutputs, unaffected);
      } else if (last == LastRequirement.INGREDIENT_ARRAY) {
        if (ingredientArrays.getLast().resource().equals("chemical")) {
          throw new IllegalStateException("Fixed parallel quantities are not supported for chemical ingredients");
        }
        KubeJSIngredientArrayBuilder.setLastGroupParallelUnaffected(ingredientArrays, unaffected);
      } else if ((last == LastRequirement.FLUID_INPUT || last == LastRequirement.FLUID_OUTPUT) && lastFluid != null) {
        lastFluid.parallelUnaffected = unaffected;
      } else {
        throw new IllegalStateException("Fixed parallel quantities require the last item or start/output fluid row");
      }
      return this;
    }

    public RecipeBuilder setIgnoreOutputCheck(boolean ignored) {
      requireMutable();
      if (last == LastRequirement.ITEM && lastItem != null) {
        if (outputs.contains(lastItem)) {
          if (lastItem.triggerTime > 0 && ignored) {
            throw new IllegalStateException("Triggered outputs require complete output space");
          }
          lastItem.ignoreOutputCheck = ignored;
        }
      } else if (last == LastRequirement.FLUID_OUTPUT && lastFluid != null) {
        if (lastFluid.triggerTime > 0 && ignored) {
          throw new IllegalStateException("Triggered outputs require complete output space");
        }
        lastFluid.ignoreOutputCheck = ignored;
      } else if (last == LastRequirement.CHEMICAL && lastChemical != null) {
        if (chemicalOutputs.contains(lastChemical)) {
          if (lastChemical.triggerTime > 0 && ignored) {
            throw new IllegalStateException("Triggered outputs require complete output space");
          }
          lastChemical.ignoreOutputCheck = ignored;
        } else if (chemicalPerTickOutputs.contains(lastChemical) && ignored) {
          throw new IllegalStateException("Per-tick outputs require complete output space");
        }
      } else if (last == LastRequirement.ENERGY_OUTPUT
          || last == LastRequirement.FLUID_PER_TICK_OUTPUT
          || last == LastRequirement.RANDOM_OUTPUT
          || last == LastRequirement.NONE) {
        if (ignored) {
          throw new IllegalStateException(
              "Ignoring output capacity requires one completion item, fluid or chemical row");
        }
      }

      return this;
    }

    public RecipeBuilder setTag(String selector) {
      requireMutable();
      ResourceSelector.require(selector);
      if (last == LastRequirement.ITEM && lastItem != null) {
        lastItem.selector = selector;
      } else if (last == LastRequirement.FUEL) {
        var fuel = fuelInputs.getLast();
        fuelInputs.set(
            fuelInputs.size() - 1,
            new ItemPaymentData.Fuel(fuel.burnTime(), fuel.chance(), fuel.parallelUnaffected(), selector));
      } else if (last == LastRequirement.RANDOM_OUTPUT) {
        KubeJSIngredientArrayBuilder.setLastGroupSelector(randomItemOutputs, selector);
      } else if (last == LastRequirement.INGREDIENT_ARRAY) {
        KubeJSIngredientArrayBuilder.setLastGroupSelector(ingredientArrays, selector);
      } else if (last == LastRequirement.ENERGY && energyRequirements == 1) {
        setEnergyInputTag(selector);
      } else if (last == LastRequirement.ENERGY_OUTPUT && energyOutputRequirements == 1) {
        setEnergyOutputTag(selector);
      } else if (last == LastRequirement.CHEMICAL && lastChemical != null) {
        lastChemical.selector = selector;
      } else if (last == LastRequirement.FLUID_INPUT
          || last == LastRequirement.FLUID_OUTPUT
          || last == LastRequirement.FLUID_PER_TICK
          || last == LastRequirement.FLUID_PER_TICK_OUTPUT) {
        lastFluid.selector = selector;
      } else {
        throw new IllegalStateException("Tags require a last item, chemical, fluid, or unique energy requirement");
      }
      return this;
    }

    public RecipeBuilder setFluidInputTag(String selector) {
      requireMutable();
      fluidInputSelector = phaseTag(fluidInputSelector, fluidInputTagged, selector);
      fluidInputTagged = true;
      return this;
    }

    public RecipeBuilder setFluidOutputTag(String selector) {
      requireMutable();
      fluidOutputSelector = phaseTag(fluidOutputSelector, fluidOutputTagged, selector);
      fluidOutputTagged = true;
      return this;
    }

    public RecipeBuilder setFluidPerTickTag(String selector) {
      requireMutable();
      fluidPerTickSelector = phaseTag(fluidPerTickSelector, fluidPerTickTagged, selector);
      fluidPerTickTagged = true;
      fluidPerTickInputs.forEach(row -> row.selector = selector);
      return this;
    }

    public RecipeBuilder setEnergyInputTag(String selector) {
      requireMutable();
      energySelector = phaseTag(energySelector, energyTagged, selector);
      energyTagged = true;
      return this;
    }

    public RecipeBuilder setEnergyOutputTag(String selector) {
      requireMutable();
      energyOutputSelector = phaseTag(energyOutputSelector, energyOutputTagged, selector);
      energyOutputTagged = true;
      return this;
    }

    private static String phaseTag(String previous, boolean assigned, String selector) {
      ResourceSelector.require(selector);
      if (assigned && !previous.equals(selector)) {
        throw new IllegalArgumentException("All requirements in one fluid/energy phase must share a selector");
      }
      return selector;
    }

    public RecipeBuilder setFailureAction(String action) {
      requireMutable();
      failureAction = RecipeFailureAction.parse(action);
      return this;
    }

    public RecipeBuilder setThreadName(String name) {
      requireMutable();
      threadName = new RecipeExecutionOptions(name, maxThreads).threadName();
      return this;
    }

    public RecipeBuilder setMaxThreads(Number maximum) {
      requireMutable();
      maxThreads = (int) KubeJSRecipeArguments
          .integer(maximum, "maximum recipe threads", 0, RecipeExecutionOptions.MAX_THREADS);
      return this;
    }

    public RecipeBuilder addRecipeTooltip(String... lines) {
      return addRecipeTooltips(List.of(lines));
    }

    public RecipeBuilder addRecipeTooltips(List<String> lines) {
      requireMutable();
      var next = new ArrayList<>(tooltips);
      next.addAll(lines);
      new RecipeScriptOptions(next, List.of());
      tooltips.clear();
      tooltips.addAll(next);
      return this;
    }

    private ItemRow requireLastItem() {
      requireMutable();
      if (last != LastRequirement.ITEM || lastItem == null) {
        throw new IllegalStateException("An item callback or preview requires the last item requirement");
      }
      return lastItem;
    }

    public RecipeBuilder setPreViewNBT(CompoundTag nbt) {
      var row = requireLastItem();
      row.preview = row.prototype.copy();
      row.preview.set(DataComponents.CUSTOM_DATA, CustomData.of(Objects.requireNonNull(nbt, "preview NBT").copy()));
      row.preview = ItemStackSnapshots.detached(row.preview, event.registries.access());
      return this;
    }

    public RecipeBuilder setPreviewStack(ItemStack stack) {
      var row = requireLastItem();
      if (stack == null || stack.isEmpty()) {
        throw new IllegalArgumentException("Recipe preview must not be empty");
      }
      row.preview = ItemStackSnapshots.detached(stack.copyWithCount(1), event.registries.access());
      return this;
    }

    public RecipeBuilder setNBTChecker(String handler) {
      var row = requireInputItem();
      row.checker = KubeJSRecipeArguments.identifier(handler, "item checker");
      row.inlineChecker = null;
      return this;
    }

    public RecipeBuilder setNBTChecker(RecipeItemCallbacks.Checker checker) {
      var row = requireInputItem();
      row.checker = KubeJSCallbackIds.identity(id, "input/" + inputs.indexOf(row) + "/checker");
      row.inlineChecker = Objects.requireNonNull(checker, "item checker");
      return this;
    }

    private ItemRow requireInputItem() {
      var row = requireLastItem();
      if (!inputs.contains(row)) {
        throw new IllegalStateException("Item checks require an input row");
      }
      return row;
    }

    private ResourceLocation modifierIdentity(ItemRow row) {
      if (row.itemModifiers.size() >= RecipeScriptOptions.MAX_ROW_MODIFIERS) {
        throw new IllegalArgumentException("Too many item modifiers in one row");
      }
      boolean input = inputs.contains(row);
      return KubeJSCallbackIds.identity(
          id,
          (input ? "input/" : "output/") + (input ? inputs.indexOf(row) : outputs
              .indexOf(row)) + "/modifier/" + row.itemModifiers.size());
    }

    public RecipeBuilder addItemModifier(String handler) {
      var row = requireLastItem();
      modifierIdentity(row);
      var callback = KubeJSRecipeArguments.identifier(handler, "item modifier");
      if (row.itemModifiers.contains(callback)) {
        throw new IllegalArgumentException("Duplicate item modifier in one row");
      }
      row.itemModifiers.add(callback);
      return this;
    }

    public RecipeBuilder addItemModifier(RecipeItemCallbacks.Modifier modifier) {
      var row = requireLastItem();
      var callback = modifierIdentity(row);
      row.itemModifiers.add(callback);
      row.inlineModifiers.put(callback, Objects.requireNonNull(modifier, "item modifier"));
      return this;
    }

    public RecipeBuilder addInputItemModifier(RecipeItemCallbacks.InputModifier modifier) {
      var row = requireInputItem();
      var callback = modifierIdentity(row);
      row.itemModifiers.add(callback);
      row.inlineInputModifiers.put(callback, Objects.requireNonNull(modifier, "input item modifier"));
      return this;
    }

    public RecipeBuilder addInputItemModifier(String handler) {
      requireInputItem();
      return addItemModifier(handler);
    }

    public RecipeBuilder addStartHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.STARTED, false, handler);
    }

    public RecipeBuilder addPreCheckHandler(RecipePreparationEvents.Handler handler) {
      return addPreparationHandler(RecipePreparationEvents.Type.PRE_CHECK, false, handler);
    }

    public RecipeBuilder addPostCheckHandler(RecipePreparationEvents.Handler handler) {
      return addPreparationHandler(RecipePreparationEvents.Type.POST_CHECK, false, handler);
    }

    /** Original deprecated entry receives both check phases. */
    @Deprecated
    public RecipeBuilder addCheckHandler(RecipePreparationEvents.Handler handler) {
      addPreCheckHandler(handler);
      return addPostCheckHandler(handler);
    }

    public RecipeBuilder addPreTickHandler(RecipePreparationEvents.Handler handler) {
      return addPreparationHandler(RecipePreparationEvents.Type.PRE_TICK, false, handler);
    }

    public RecipeBuilder addFactoryPreTickHandler(RecipePreparationEvents.Handler handler) {
      return addPreparationHandler(RecipePreparationEvents.Type.PRE_TICK, true, handler);
    }

    private RecipeBuilder addPreparationHandler(RecipePreparationEvents.Type type, boolean factoryOnly, RecipePreparationEvents.Handler handler) {
      requireMutable();
      if (preparationHandlers.size() >= 256) {
        throw new IllegalArgumentException("Too many inline recipe preparation handlers");
      }
      preparationHandlers.add(
          new RecipePreparationHandlers.Handler(
              KubeJSCallbackIds.identity(id, "preparation/" + preparationHandlers.size()),
              machine,
              id,
              type,
              factoryOnly,
              Objects.requireNonNull(handler, "recipe preparation handler")));
      return this;
    }

    public RecipeBuilder addPostTickHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.TICK, false, handler);
    }

    public RecipeBuilder addFinishHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.FINISHED, false, handler);
    }

    public RecipeBuilder addFactoryStartHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.STARTED, true, handler);
    }

    public RecipeBuilder addFactoryFinishHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.FINISHED, true, handler);
    }

    public RecipeBuilder addFailureHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.RESOURCE_FAILURE, false, handler);
    }

    public RecipeBuilder addFactoryFailureHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.RESOURCE_FAILURE, true, handler);
    }

    public RecipeBuilder addFactoryPostTickHandler(RecipeLifecycleEvents.Listener handler) {
      return addLifecycleHandler(RecipeLifecycleEvents.Type.TICK, true, handler);
    }

    private RecipeBuilder addLifecycleHandler(RecipeLifecycleEvents.Type type, boolean factoryOnly, RecipeLifecycleEvents.Listener handler) {
      requireMutable();
      if (lifecycleHandlers.size() >= 256) {
        throw new IllegalArgumentException("Too many inline recipe lifecycle handlers");
      }
      lifecycleHandlers.add(
          new RecipeDeclaredObservers.Handler(
              KubeJSCallbackIds.identity(id, "lifecycle/" + lifecycleHandlers.size()),
              machine,
              id,
              type,
              factoryOnly,
              Objects.requireNonNull(handler, "recipe listener")));
      return this;
    }

    public RecipeBuilder setMinMaxAmount(Number minimum, Number maximum) {
      return setItemRange(
          KubeJSRecipeArguments.integer(minimum, "minimum item amount", 0, Long.MAX_VALUE),
          KubeJSRecipeArguments.integer(maximum, "maximum item amount", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder setMinMaxAmount(String minimum, String maximum) {
      return setItemRange(
          KubeJSRecipeArguments.integer(minimum, "minimum item amount", 0, Long.MAX_VALUE),
          KubeJSRecipeArguments.integer(maximum, "maximum item amount", 1, Long.MAX_VALUE));
    }

    private RecipeBuilder setItemRange(long min, long max) {
      requireMutable();
      if (min > max) {
        throw new IllegalArgumentException("Minimum item amount exceeds maximum");
      }
      if (last == LastRequirement.INGREDIENT_ARRAY) {
        var group = ingredientArrays.getLast();
        if (!group.resource().equals("item")) {
          throw new IllegalStateException("Quantity ranges require an item ingredient group");
        }
        var choices = group.alternatives().stream()
            .map(
                choice -> new IngredientArrayData.Choice(
                    choice.resource(),
                    choice.item(),
                    choice.fluid(),
                    choice.chemical(),
                    max,
                    choice.chance(),
                    choice.checker(),
                    choice.modifiers(),
                    min,
                    max,
                    choice.match()))
            .toList();
        ingredientArrays.set(
            ingredientArrays.size() - 1,
            new IngredientArrayData.Group(
                choices,
                group.chance(),
                group.parallelUnaffected(),
                group.selector(),
                group.optional(),
                group.tooltips(),
                group.recipeModifiers()));
        return this;
      }
      if (last != LastRequirement.ITEM || lastItem == null) {
        throw new IllegalStateException("setMinMaxAmount requires an item row");
      }
      lastItem.amount = max;
      lastItem.minAmount = min;
      lastItem.maxAmount = max;

      return this;
    }

    public RecipeBuilder setLoadJEI(boolean load) {
      requireMutable();
      loadJei = load;

      return this;
    }

    private RecipeScriptOptions scriptOptions() {
      var callbacks = new ArrayList<RecipeScriptOptions.ItemCallbacks>();
      collectCallbacks(callbacks, inputs, RecipeModifier.Io.INPUT);
      collectCallbacks(callbacks, outputs, RecipeModifier.Io.OUTPUT);
      return new RecipeScriptOptions(tooltips, callbacks, loadJei);
    }

    private void collectCallbacks(List<RecipeScriptOptions.ItemCallbacks> callbacks, List<ItemRow> rows, RecipeModifier.Io io) {
      int visibleIndex = 0;
      for (int index = 0; index < rows.size(); index++) {
        var row = rows.get(index);
        if (row.triggerTime > 0) {
          if (row.checker != null || !row.itemModifiers.isEmpty()) {
            throw new IllegalStateException("Triggered item rows cannot attach item callbacks");
          }
          continue;
        }
        if (row.checker != null || !row.itemModifiers.isEmpty()) {
          callbacks.add(
              new RecipeScriptOptions.ItemCallbacks(
                  io,
                  visibleIndex,
                  Optional.ofNullable(row.checker),
                  row.itemModifiers));
        }
        visibleIndex++;
      }
    }

    private RecipeScriptCallbacks.Bindings callbacks() {
      var checkers = new LinkedHashMap<ResourceLocation, RecipeItemCallbacks.Checker>();
      var modifiers = new LinkedHashMap<ResourceLocation, RecipeItemCallbacks.Modifier>();
      var inputModifiers = new LinkedHashMap<ResourceLocation, RecipeItemCallbacks.InputModifier>();
      for (var group : ingredientArrayCallbacks) {
        checkers.putAll(group.checkers());
        modifiers.putAll(group.modifiers());
      }
      for (var rows : List.of(inputs, outputs)) {
        for (var row : rows) {
          if (row.inlineChecker != null) {
            checkers.put(row.checker, row.inlineChecker);
          }
          modifiers.putAll(row.inlineModifiers);
          inputModifiers.putAll(row.inlineInputModifiers);
        }
      }
      return new RecipeScriptCallbacks.Bindings(
          checkers,
          modifiers,
          inputModifiers,
          lifecycleHandlers,
          preparationHandlers);
    }

    public void build(Context context) {
      requireMutable();
      requireServerScript(context);
      var declaration = MachineRecipeCompiler.capture(declaration(), event.registries.access());
      MachineRecipeCompiler.compile(declaration);
      var bindings = callbacks();
      built = true;
      var registration = ((MachineItemRecipeKubeJSSources.RecipeEvent) event).MMCECE$registration();
      registration.submit(context, event, id, declaration, bindings);
    }

    private MachineRecipeDeclaration declaration() {
      var tools = new ArrayList<ItemPaymentData.Tool>();
      int visibleToolRow = 0;
      for (int row = 0; row < inputs.size(); row++) {
        if (inputs.get(row).triggerTime > 0) {
          if (inputs.get(row).consumeDurability > 0) {
            throw new IllegalStateException("Triggered inputs cannot combine durability payment");
          }
          continue;
        }
        if (inputs.get(row).consumeDurability > 0) {
          tools.add(new ItemPaymentData.Tool(visibleToolRow, inputs.get(row).consumeDurability));
        }
        visibleToolRow++;
      }
      var items = new ItemRecipeData(itemRows(inputs), itemRows(outputs), new ItemPaymentData(tools, fuelInputs));
      var fluids = new FluidRecipeData(
          ordinaryFluids(fluidInputs),
          ordinaryFluids(fluidOutputs),
          fluidPerTickInputs.stream().map(FluidRow::requirement).toList(),
          fluidPerTickOutputs.stream().map(FluidRow::requirement).toList());
      var chemicals = new ChemicalRecipeData(
          chemicalInputs.stream().filter(row -> row.triggerTime == 0).map(ChemicalRow::requirement).toList(),
          chemicalOutputs.stream().filter(row -> row.triggerTime == 0).map(ChemicalRow::requirement).toList(),
          chemicalPerTick.stream().map(ChemicalRow::requirement).toList(),
          chemicalPerTickOutputs.stream().map(ChemicalRow::requirement).toList());
      return new MachineRecipeDeclaration(
          machine,
          priority,
          duration,
          resources(
              new RecipeResources.Entry<>(RecipeResourceDefinitions.ITEM, items),
              new RecipeResources.Entry<>(RecipeResourceDefinitions.FLUID, fluids),
              new RecipeResources.Entry<>(
                  RecipeResourceDefinitions.ENERGY,
                  new EnergyRecipeData(energyPerTick, energyOutputPerTick)),
              new RecipeResources.Entry<>(RecipeResourceDefinitions.CHEMICAL, chemicals)),
          Optional.ofNullable(failureAction),
          Optional.ofNullable(parallelized),
          randomSeed,
          interfaceInputs,
          new RecipeSelectors(
              fluidInputSelector,
              fluidOutputSelector,
              energySelector,
              fluidPerTickSelector,
              energyOutputSelector),
          new RecipeExecutionOptions(threadName, maxThreads),
          scriptOptions(),
          new IngredientArrayData(ingredientArrays, randomItemOutputs),
          triggeredRows(),
          modifiers,
          false);
    }

    private static List<ItemAmount> itemRows(List<ItemRow> rows) {
      return rows.stream().filter(row -> row.triggerTime == 0)
          .map(
              row -> new ItemAmount(
                  row.prototype,
                  row.amount,
                  row.chance,
                  row.selector,
                  row.match,
                  Objects.requireNonNullElse(row.preview, ItemStack.EMPTY),
                  row.parallelUnaffected,
                  row.minAmount,
                  row.maxAmount,
                  row.ignoreOutputCheck))
          .toList();
    }

    private static List<FluidRequirement> ordinaryFluids(List<FluidRow> rows) {
      return rows.stream().filter(row -> row.triggerTime == 0).map(FluidRow::requirement).toList();
    }

    private TriggeredResourceData triggeredRows() {
      var rows = new ArrayList<TriggeredResourceData.Row>();
      for (var source : List.of(inputs, outputs)) {
        for (var row : source) {
          if (row.triggerTime > 0) {
            if (row.preview != null) {
              throw new IllegalStateException("Triggered item rows cannot use an item preview");
            }
            if (row.minAmount != 0 || row.maxAmount != 0 || row.consumeDurability != 0) {
              throw new IllegalStateException(
                  "Triggered item rows cannot combine random quantities or durability payment");
            }
            rows.add(
                new TriggeredResourceData.Row(
                    "item",
                    source == inputs ? RecipeModifier.Io.INPUT : RecipeModifier.Io.OUTPUT,
                    row.triggerTime,
                    row.triggerRepeatable,
                    row.amount,
                    row.chance,
                    row.selector,
                    Optional.of(row.prototype),
                    Optional.empty(),
                    Optional.empty(),
                    row.parallelUnaffected,
                    row.match));
          }
        }
      }
      for (var source : List.of(fluidInputs, fluidOutputs)) {
        for (var row : source) {
          if (row.triggerTime > 0) {
            rows.add(
                new TriggeredResourceData.Row(
                    "fluid",
                    source == fluidInputs ? RecipeModifier.Io.INPUT : RecipeModifier.Io.OUTPUT,
                    row.triggerTime,
                    row.triggerRepeatable,
                    row.stack.getAmount(),
                    row.chance,
                    Objects.requireNonNullElse(
                        row.selector,
                        source == fluidInputs ? fluidInputSelector : fluidOutputSelector),
                    Optional.empty(),
                    Optional.of(row.stack.copyWithAmount(1)),
                    Optional.empty(),
                    row.parallelUnaffected,
                    "exact"));
          }
        }
      }
      for (var source : List.of(chemicalInputs, chemicalOutputs)) {
        for (var row : source) {
          if (row.triggerTime > 0) {
            rows.add(
                new TriggeredResourceData.Row(
                    "chemical",
                    source == chemicalInputs ? RecipeModifier.Io.INPUT : RecipeModifier.Io.OUTPUT,
                    row.triggerTime,
                    row.triggerRepeatable,
                    row.amount,
                    row.chance,
                    row.selector,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(row.chemical),
                    false,
                    "exact"));
          }
        }
      }
      return new TriggeredResourceData(rows);
    }

    private <T> JsonElement encode(Codec<T> codec, T value) {
      return codec.encodeStart(event.registries.access().createSerializationContext(JsonOps.INSTANCE), value)
          .getOrThrow();
    }
  }
}
