package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Internal server-recipe facade; only build attaches a native declaration. */
@ApiStatus.Internal
public final class KubeJSRecipeBuilder {
  private KubeJSRecipeBuilder() {
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

  public static final class RecipeBuilder {
    private enum LastRequirement {
      NONE,
      ITEM,
      ENERGY,
      FLUID,
      CHEMICAL
    }

    private static final class ItemRow {
      final ItemStack prototype;
      final long amount;
      double chance = 1;
      String selector = "";

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

    private final RecipesKubeEvent event;
    private final ResourceLocation id, machine;
    private final int duration, priority;
    private final Thread owner = Thread.currentThread();
    private final List<ItemRow> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private final List<FluidStack> fluidInputs = new ArrayList<>(), fluidOutputs = new ArrayList<>();
    private final List<ChemicalRow> chemicalInputs = new ArrayList<>(), chemicalOutputs = new ArrayList<>(),
        chemicalPerTick = new ArrayList<>();
    private FluidStack fluidPerTick = FluidStack.EMPTY;
    private long energyPerTick;
    private int energyRequirements;
    private String energySelector = "";
    private Boolean parallelized;
    private RecipeFailureAction failureAction;
    private LastRequirement last = LastRequirement.NONE;
    private ItemRow lastItem;
    private ChemicalRow lastChemical;
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

    public RecipeBuilder addItemInput(ItemStack stack, Number amount) {
      return addItem(inputs, stack, amount);
    }

    public RecipeBuilder addItemOutput(ItemStack stack) {
      return addItemOutput(stack, stack == null ? null : stack.getCount());
    }

    public RecipeBuilder addItemOutput(ItemStack stack, Number amount) {
      return addItem(outputs, stack, amount);
    }

    private RecipeBuilder addItem(List<ItemRow> rows, ItemStack stack, Number amount) {
      requireMutable();
      if (stack == null || stack.isEmpty()) {
        throw new IllegalArgumentException("Machine recipe item requirement must not be empty");
      }
      if (rows.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many machine recipe item requirements");
      }
      var row = new ItemRow(
          ItemStackSnapshots.detached(stack.copyWithCount(1), event.registries.access()),
          integer(amount, "item amount", 1, Long.MAX_VALUE));
      rows.add(row);
      last = LastRequirement.ITEM;
      lastItem = row;
      return this;
    }

    public RecipeBuilder addEnergyPerTickInput(Number amount) {
      requireMutable();
      if (!energySelector.isEmpty()) {
        throw new IllegalStateException("Additional energy requirements cannot change a tagged energy cohort");
      }
      energyPerTick = Math.addExact(energyPerTick, integer(amount, "FE/t", 0, Long.MAX_VALUE));
      energyRequirements = Math.incrementExact(energyRequirements);
      last = LastRequirement.ENERGY;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addFluidInput(FluidStack fluid) {
      requireMutable();
      if (fluidInputs.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many machine recipe fluid inputs");
      }
      fluidInputs.add(detachedFluid(fluid));
      last = LastRequirement.FLUID;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addFluidOutput(FluidStack fluid) {
      requireMutable();
      if (fluidOutputs.size() >= MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many machine recipe fluid outputs");
      }
      fluidOutputs.add(detachedFluid(fluid));
      last = LastRequirement.FLUID;
      lastItem = null;
      return this;
    }

    public RecipeBuilder addFluidPerTickInput(FluidStack fluid) {
      requireMutable();
      if (!fluidPerTick.isEmpty()) {
        throw new IllegalStateException("Only one fluid-per-tick requirement is supported");
      }
      fluidPerTick = detachedFluid(fluid);
      last = LastRequirement.FLUID;
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
      if ((value instanceof Double || value instanceof Float) && Math.abs(value.doubleValue()) > 9007199254740991D) {
        throw new IllegalArgumentException("Large chemical amounts require an exact decimal string");
      }

      return integer(value, "chemical amount", 1, Long.MAX_VALUE);
    }

    private static long chemicalAmount(String value) {
      Objects.requireNonNull(value, "chemical amount");
      try {
        return integer(new BigDecimal(value), "chemical amount", 1, Long.MAX_VALUE);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("Chemical amount requires an exact decimal integer", invalid);
      }
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

    public RecipeBuilder setChance(Number chance) {
      requireMutable();
      if (last != LastRequirement.ITEM || lastItem == null) {
        throw new IllegalStateException("Chance applies only to the last item requirement");
      }
      Objects.requireNonNull(chance, "chance");
      double value = chance.doubleValue();
      if (!Double.isFinite(value) || value < 0 || value > 1) {
        throw new IllegalArgumentException("Chance must be finite and within 0..1");
      }
      lastItem.chance = value;
      return this;
    }

    public RecipeBuilder setTag(String selector) {
      requireMutable();
      ResourceSelector.require(selector);
      if (last == LastRequirement.ITEM && lastItem != null) {
        lastItem.selector = selector;
      } else if (last == LastRequirement.ENERGY && energyRequirements == 1) {
        energySelector = selector;
      } else if (last == LastRequirement.CHEMICAL && lastChemical != null) {
        lastChemical.selector = selector;
      } else {
        throw new IllegalStateException(
            "Tags require a last item, chemical, or unique energy requirement; per-row fluid tags are unsupported");
      }
      return this;
    }

    public RecipeBuilder setFailureAction(String action) {
      requireMutable();
      failureAction = RecipeFailureAction.parse(action);
      return this;
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
      if (!energySelector.isEmpty()) {
        var selectors = new JsonObject();
        selectors.addProperty("energy_input", energySelector);
        json.add("selectors", selectors);
      }
      if (parallelized != null) {
        json.addProperty("parallelized", parallelized);
      }
      if (failureAction != null) {
        json.addProperty("failure_action", failureAction.serialized());
      }
      if (!fluidPerTick.isEmpty()) {
        json.add("fluid_per_tick", encode(FluidStack.CODEC, fluidPerTick));
      }
      if (!fluidInputs.isEmpty()) {
        json.add("fluid_inputs", encode(FluidStack.CODEC.listOf(), fluidInputs));
      }
      if (!fluidOutputs.isEmpty()) {
        json.add("fluid_outputs", encode(FluidStack.CODEC.listOf(), fluidOutputs));
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
    }

    private JsonArray itemRows(List<ItemRow> rows) {
      var array = new JsonArray();
      for (var row : rows) {
        var json = new JsonObject();
        json.add("stack", encode(ItemStack.SINGLE_ITEM_CODEC, row.prototype));
        json.addProperty("amount", row.amount);
        if (row.chance != 1) {
          json.addProperty("chance", row.chance);
        }
        if (!row.selector.isEmpty()) {
          json.addProperty("selector", row.selector);
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
