package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.KubeResourceLocation;
import dev.latvian.mods.rhino.Context;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
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

  public static RecipeBuilder newBuilder(Context context, RecipesKubeEvent event, String recipeName, String machineName, Number processingTickTime) {
    return newBuilder(context, event, recipeName, machineName, processingTickTime, 0);
  }

  public static RecipeBuilder newBuilder(Context context, RecipesKubeEvent event, String recipeName, String machineName, Number processingTickTime, Number sortingPriority) {
    requireServerScript(context);
    Objects.requireNonNull(event, "recipe event");
    var id = identifier(recipeName, "recipe");
    if (recipeName.indexOf(':') < 0) {
      id = ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, id.getPath());
    }
    return new RecipeBuilder(
        event,
        id,
        identifier(machineName, "machine"),
        (int) integer(processingTickTime, "processing tick time", 1, Integer.MAX_VALUE),
        (int) integer(sortingPriority, "sorting priority", Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  private static void requireServerScript(Context context) {
    if (!(context instanceof KubeJSContext kubeContext) || kubeContext.getType() != ScriptType.SERVER) {
      throw new IllegalStateException("Machine recipe builders require a server recipe script");
    }
  }

  private static ResourceLocation identifier(String text, String field) {
    var id = text == null ? null : ResourceLocation.tryParse(text);
    if (id == null) {
      throw new IllegalArgumentException("Invalid machine recipe " + field + " ID: " + text);
    }
    return id;
  }

  private static long integer(Number value, String field, long minimum, long maximum) {
    Objects.requireNonNull(value, field);
    if ((value instanceof Double || value instanceof Float) && Math.abs(value.doubleValue()) > 9007199254740991D) {
      throw new IllegalArgumentException("Large " + field + " values require an exact decimal string");
    }
    final long amount;
    try {
      amount = new BigDecimal(value.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " requires an exact integer", invalid);
    }
    if (amount < minimum || amount > maximum) {
      throw new IllegalArgumentException(field + " is outside its integer range");
    }
    return amount;
  }

  private static long integer(String value, String field, long minimum, long maximum) {
    Objects.requireNonNull(value, field);
    if (!value.matches("[0-9]+")) {
      throw new IllegalArgumentException(field + " requires an exact decimal integer string");
    }
    return integer(new BigDecimal(value), field, minimum, maximum);
  }

  public static final class RecipeBuilder {
    private enum LastRequirement {
      NONE,
      ITEM,
      ENERGY,
      ENERGY_OUTPUT,
      FLUID_INPUT,
      FLUID_OUTPUT,
      FLUID_PER_TICK,
      CHEMICAL
    }

    private static final class ItemRow {
      final ItemStack prototype;
      final long amount;
      double chance = 1;
      boolean parallelUnaffected;
      String selector = "";
      String match = "exact";
      ItemStack preview;
      ResourceLocation checker;
      RecipeItemCallbacks.Checker inlineChecker;
      final List<ResourceLocation> itemModifiers = new ArrayList<>();
      final Map<ResourceLocation, RecipeItemCallbacks.Modifier> inlineModifiers = new LinkedHashMap<>();
      final Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inlineInputModifiers = new LinkedHashMap<>();

      ItemRow(ItemStack prototype, long amount) {
        this.prototype = prototype;
        this.amount = amount;
      }
    }

    private static final class ChemicalRow {
      final ResourceLocation chemical;
      final long amount;
      String selector = "";

      ChemicalRow(ResourceLocation chemical, long amount) {
        this.chemical = chemical;
        this.amount = amount;
      }

      ChemicalRequirement requirement() {
        return new ChemicalRequirement(chemical, amount, selector);
      }
    }

    private static final class FluidRow {
      final FluidStack stack;
      double chance = 1;
      boolean parallelUnaffected;

      FluidRow(FluidStack stack) {
        this.stack = stack;
      }

      FluidRequirement requirement() {
        return new FluidRequirement(stack, chance, parallelUnaffected);
      }
    }

    private final RecipesKubeEvent event;
    private final ResourceLocation id, machine;
    private final int duration, priority;
    private final Thread owner = Thread.currentThread();
    private final List<ItemRow> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private final List<FluidRow> fluidInputs = new ArrayList<>(), fluidOutputs = new ArrayList<>();
    private final List<ChemicalRow> chemicalInputs = new ArrayList<>(), chemicalOutputs = new ArrayList<>(),
        chemicalPerTick = new ArrayList<>();
    private final List<SmartNumberRequirement> interfaceInputs = new ArrayList<>();
    private final List<RecipeModifier> modifiers = new ArrayList<>();
    private final List<String> tooltips = new ArrayList<>();
    private final List<KubeJSRecipeBuilderHandlers.Handler> lifecycleHandlers = new ArrayList<>();
    private FluidStack fluidPerTick = FluidStack.EMPTY;
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

    private void requireMutable() {
      if (built || Thread.currentThread() != owner) {
        throw new IllegalStateException("Machine recipe builder is built or used outside its recipe thread");
      }
    }

    public RecipeBuilder addItemInput(ItemStack stack) {
      return addItemInput(stack, stack == null ? null : stack.getCount());
    }

    public RecipeBuilder addInput(ItemStack stack) {
      return addItemInput(stack);
    }

    public RecipeBuilder addInput(FluidStack stack) {
      return addFluidInput(stack);
    }

    public RecipeBuilder addOutput(ItemStack stack) {
      return addItemOutput(stack);
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
            addItemOutput(item);
          } else {
            addItemInput(item);
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

    public RecipeBuilder addItemInput(ItemStack stack, Number amount) {
      return addItem(inputs, stack, integer(amount, "item amount", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder addItemInput(ItemStack stack, String amount) {
      return addItem(inputs, stack, integer(amount, "item amount", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder addItemInputIgnoringComponents(ItemStack stack) {
      return addItemInputIgnoringComponents(stack, stack == null ? null : stack.getCount());
    }

    public RecipeBuilder addItemInputIgnoringComponents(ItemStack stack, Number amount) {
      return addItemIgnoringComponents(stack, integer(amount, "item amount", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder addItemInputIgnoringComponents(ItemStack stack, String amount) {
      return addItemIgnoringComponents(stack, integer(amount, "item amount", 1, Long.MAX_VALUE));
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

    public RecipeBuilder addItemOutput(ItemStack stack) {
      return addItemOutput(stack, stack == null ? null : stack.getCount());
    }

    public RecipeBuilder addItemOutput(ItemStack stack, Number amount) {
      return addItem(outputs, stack, integer(amount, "item amount", 1, Long.MAX_VALUE));
    }

    public RecipeBuilder addItemOutput(ItemStack stack, String amount) {
      return addItem(outputs, stack, integer(amount, "item amount", 1, Long.MAX_VALUE));
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
      return addEnergy(integer(amount, "FE/t", 0, Long.MAX_VALUE));
    }

    public RecipeBuilder addEnergyPerTickInput(String amount) {
      return addEnergy(integer(amount, "FE/t", 0, Long.MAX_VALUE));
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
      return addEnergyOutput(integer(amount, "output FE/t", 0, Long.MAX_VALUE));
    }

    public RecipeBuilder addEnergyPerTickOutput(String amount) {
      return addEnergyOutput(integer(amount, "output FE/t", 0, Long.MAX_VALUE));
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
      requireMutable();
      if (!fluidPerTick.isEmpty()) {
        throw new IllegalStateException("Only one fluid-per-tick requirement is supported");
      }
      fluidPerTick = detachedFluid(fluid);
      last = LastRequirement.FLUID_PER_TICK;
      lastItem = null;
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

    private RecipeBuilder addChemical(List<ChemicalRow> rows, String chemical, long amount) {
      requireMutable();
      if (rows.size() >= ChemicalRecipeData.MAX_REQUIREMENTS) {
        throw new IllegalArgumentException("Too many machine recipe chemical requirements");
      }
      var row = new ChemicalRow(identifier(chemical, "chemical"), amount);
      rows.add(row);
      last = LastRequirement.CHEMICAL;
      lastChemical = row;
      lastItem = null;
      return this;
    }

    private static long chemicalAmount(Number value) {
      return integer(value, "chemical amount", 1, Long.MAX_VALUE);
    }

    private static long chemicalAmount(String value) {
      return integer(value, "chemical amount", 1, Long.MAX_VALUE);
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
      randomSeed = integer(seed, "random seed", 0, Long.MAX_VALUE);
      return this;
    }

    public RecipeBuilder setRandomSeed(String seed) {
      requireMutable();
      randomSeed = integer(seed, "random seed", 0, Long.MAX_VALUE);
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
      } else if ((last == LastRequirement.FLUID_INPUT || last == LastRequirement.FLUID_OUTPUT) && lastFluid != null) {
        lastFluid.chance = value;
      } else {
        throw new IllegalStateException("Chance requires the last item or start/output fluid requirement");
      }
      return this;
    }

    public RecipeBuilder setParallelizeUnaffected(boolean unaffected) {
      requireMutable();
      if (last == LastRequirement.ITEM && lastItem != null) {
        lastItem.parallelUnaffected = unaffected;
      } else if ((last == LastRequirement.FLUID_INPUT || last == LastRequirement.FLUID_OUTPUT) && lastFluid != null) {
        lastFluid.parallelUnaffected = unaffected;
      } else {
        throw new IllegalStateException("Fixed parallel quantities require the last item or start/output fluid row");
      }
      return this;
    }

    public RecipeBuilder setTag(String selector) {
      requireMutable();
      ResourceSelector.require(selector);
      if (last == LastRequirement.ITEM && lastItem != null) {
        lastItem.selector = selector;
      } else if (last == LastRequirement.ENERGY && energyRequirements == 1) {
        setEnergyInputTag(selector);
      } else if (last == LastRequirement.ENERGY_OUTPUT && energyOutputRequirements == 1) {
        setEnergyOutputTag(selector);
      } else if (last == LastRequirement.CHEMICAL && lastChemical != null) {
        lastChemical.selector = selector;
      } else if (last == LastRequirement.FLUID_INPUT) {
        setFluidInputTag(selector);
      } else if (last == LastRequirement.FLUID_OUTPUT) {
        setFluidOutputTag(selector);
      } else if (last == LastRequirement.FLUID_PER_TICK) {
        setFluidPerTickTag(selector);
      } else {
        throw new IllegalStateException(
            "Tags require a last item, chemical, fluid phase, or unique energy requirement");
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
      maxThreads = (int) integer(maximum, "maximum recipe threads", 0, RecipeExecutionOptions.MAX_THREADS);
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
      row.checker = identifier(handler, "item checker");
      row.inlineChecker = null;
      return this;
    }

    public RecipeBuilder setNBTChecker(RecipeItemCallbacks.Checker checker) {
      var row = requireInputItem();
      row.checker = KubeJSRecipeItemCallbacks.identity(id, "input/" + inputs.indexOf(row) + "/checker");
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
      return KubeJSRecipeItemCallbacks.identity(
          id,
          (input ? "input/" : "output/") + (input ? inputs.indexOf(row) : outputs
              .indexOf(row)) + "/modifier/" + row.itemModifiers.size());
    }

    public RecipeBuilder addItemModifier(String handler) {
      var row = requireLastItem();
      modifierIdentity(row);
      var callback = identifier(handler, "item modifier");
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

    private RecipeBuilder addLifecycleHandler(RecipeLifecycleEvents.Type type, boolean factoryOnly, RecipeLifecycleEvents.Listener handler) {
      requireMutable();
      if (lifecycleHandlers.size() >= 256) {
        throw new IllegalArgumentException("Too many inline recipe lifecycle handlers");
      }
      lifecycleHandlers.add(
          new KubeJSRecipeBuilderHandlers.Handler(
              KubeJSRecipeItemCallbacks.identity(id, "lifecycle/" + lifecycleHandlers.size()),
              machine,
              id,
              type,
              factoryOnly,
              Objects.requireNonNull(handler, "recipe listener")));
      return this;
    }

    private RecipeScriptOptions scriptOptions() {
      var callbacks = new ArrayList<RecipeScriptOptions.ItemCallbacks>();
      collectCallbacks(callbacks, inputs, RecipeModifier.Io.INPUT);
      collectCallbacks(callbacks, outputs, RecipeModifier.Io.OUTPUT);
      return new RecipeScriptOptions(tooltips, callbacks);
    }

    private void collectCallbacks(List<RecipeScriptOptions.ItemCallbacks> callbacks, List<ItemRow> rows, RecipeModifier.Io io) {
      for (int index = 0; index < rows.size(); index++) {
        var row = rows.get(index);
        if (row.checker != null || !row.itemModifiers.isEmpty()) {
          callbacks.add(
              new RecipeScriptOptions.ItemCallbacks(io, index, Optional.ofNullable(row.checker), row.itemModifiers));
        }
      }
    }

    private void captureCallbacks() {
      lifecycleHandlers.forEach(KubeJSRecipeItemCallbacks::lifecycle);
      for (var rows : List.of(inputs, outputs)) {
        for (var row : rows) {
          if (row.inlineChecker != null) {
            KubeJSRecipeItemCallbacks.checker(row.checker, row.inlineChecker);
          }
          row.inlineModifiers.forEach(KubeJSRecipeItemCallbacks::modifier);
          row.inlineInputModifiers.forEach(KubeJSRecipeItemCallbacks::inputModifier);
        }
      }
    }

    public void build(Context context) {
      requireMutable();
      requireServerScript(context);
      var json = new JsonObject();
      json.addProperty("type", MachineRecipeJson.TYPE);
      json.addProperty("machine", machine.toString());
      json.addProperty("duration", duration);
      json.addProperty("priority", priority);
      json.add("inputs", itemRows(inputs));
      json.add("outputs", itemRows(outputs));
      if (energyPerTick > 0) {
        json.addProperty("energy_per_tick", energyPerTick);
      }
      if (energyOutputPerTick > 0) {
        json.addProperty("energy_output_per_tick", energyOutputPerTick);
      }
      var execution = encode(RecipeExecutionOptions.CODEC, new RecipeExecutionOptions(threadName, maxThreads))
          .getAsJsonObject();
      execution.entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
      var script = encode(RecipeScriptOptions.CODEC.codec(), scriptOptions()).getAsJsonObject();
      script.entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
      var selectors = new RecipeSelectors(
          fluidInputSelector,
          fluidOutputSelector,
          energySelector,
          fluidPerTickSelector,
          energyOutputSelector);
      if (!selectors.equals(RecipeSelectors.NONE)) {
        json.add("selectors", encode(RecipeSelectors.CODEC, selectors));
      }
      if (parallelized != null) {
        json.addProperty("parallelized", parallelized);
      }
      if (failureAction != null) {
        json.addProperty("failure_action", failureAction.serialized());
      }
      if (randomSeed > 0) {
        json.addProperty("random_seed", randomSeed);
      }
      if (!interfaceInputs.isEmpty()) {
        json.add("interface_inputs", encode(SmartNumberRequirement.CODEC.listOf(), interfaceInputs));
      }
      if (!modifiers.isEmpty()) {
        json.add("modifiers", encode(RecipeModifier.CODEC.listOf(), modifiers));
      }
      if (!fluidPerTick.isEmpty()) {
        json.add("fluid_per_tick", encode(FluidStack.CODEC, fluidPerTick));
      }
      if (!fluidInputs.isEmpty()) {
        json.add(
            "fluid_inputs",
            encode(FluidRequirement.CODEC.listOf(), fluidInputs.stream().map(FluidRow::requirement).toList()));
      }
      if (!fluidOutputs.isEmpty()) {
        json.add(
            "fluid_outputs",
            encode(FluidRequirement.CODEC.listOf(), fluidOutputs.stream().map(FluidRow::requirement).toList()));
      }
      var chemicals = new ChemicalRecipeData(
          chemicalInputs.stream().map(ChemicalRow::requirement).toList(),
          chemicalOutputs.stream().map(ChemicalRow::requirement).toList(),
          chemicalPerTick.stream().map(ChemicalRow::requirement).toList());
      if (!chemicals.isEmpty()) {
        json.add("chemicals", encode(ChemicalRecipeData.CODEC, chemicals));
      }
      MachineRecipeJson.validate(json);
      MachineRecipe.CODEC.codec().parse(event.registries.access().createSerializationContext(JsonOps.INSTANCE), json)
          .getOrThrow();
      built = true;
      var recipe = event.custom(context, json);
      if (recipe == null || recipe.creationError) {
        throw new IllegalStateException("KubeJS rejected the native machine recipe declaration");
      }
      recipe.id(new KubeResourceLocation(id));
      captureCallbacks();
    }

    private JsonArray itemRows(List<ItemRow> rows) {
      var array = new JsonArray();
      for (var row : rows) {
        var json = new JsonObject();
        json.add("stack", encode(ItemStack.SINGLE_ITEM_CODEC, row.prototype));
        if (row.preview != null) {
          json.add("preview_stack", encode(ItemStack.SINGLE_ITEM_CODEC, row.preview));
        }
        json.addProperty("amount", row.amount);
        if (row.chance != 1) {
          json.addProperty("chance", row.chance);
        }
        if (row.parallelUnaffected) {
          json.addProperty("parallel_unaffected", true);
        }
        if (!row.selector.isEmpty()) {
          json.addProperty("selector", row.selector);
        }
        if (!row.match.equals("exact")) {
          json.addProperty("match", row.match);
        }
        array.add(json);
      }
      return array;
    }

    private <T> JsonElement encode(Codec<T> codec, T value) {
      return codec.encodeStart(event.registries.access().createSerializationContext(JsonOps.INSTANCE), value)
          .getOrThrow();
    }
  }
}
