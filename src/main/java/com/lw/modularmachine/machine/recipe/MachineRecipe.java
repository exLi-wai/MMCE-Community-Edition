package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import net.minecraft.nbt.NbtOps;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.nbt.CompoundTag;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import java.util.stream.IntStream;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;

/**
 * Native data definition. This recipe cannot assemble through a crafting grid
 * or bypass machine admission.
 */
public final class MachineRecipe implements Recipe<RecipeInput> {
  private record Extras(
      RecipeExecutionOptions executionOptions,
      RecipeScriptOptions scriptOptions,
      IngredientArrayData ingredientArrays,
      TriggeredResourceData triggeredResources) {
    private static final MapCodec<Extras> CODEC = RecordCodecBuilder
        .mapCodec(
            instance -> instance
                .group(
                    RecipeExecutionOptions.MAP_CODEC.forGetter(Extras::executionOptions),
                    RecipeScriptOptions.CODEC.forGetter(Extras::scriptOptions),
                    IngredientArrayData.MAP_CODEC.forGetter(Extras::ingredientArrays),
                    TriggeredResourceData.MAP_CODEC.forGetter(Extras::triggeredResources))
                .apply(instance, Extras::new));

    private Extras {
      Objects.requireNonNull(executionOptions, "executionOptions");
      Objects.requireNonNull(scriptOptions, "scriptOptions");
    }
  }
  public static final class ItemAmount {
    public static final Codec<ItemAmount> CODEC = RecordCodecBuilder.create(
        instance -> instance
            .group(
                ItemStack.SINGLE_ITEM_CODEC.fieldOf("stack").forGetter((ItemAmount value) -> value.prototype),
                Codec.LONG.fieldOf("amount").forGetter(ItemAmount::amount),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(ItemAmount::chance),
                Codec.STRING.optionalFieldOf("selector", "").forGetter(ItemAmount::selector),
                Codec.STRING.optionalFieldOf("match", "exact").forGetter(ItemAmount::match),
                ItemStack.SINGLE_ITEM_CODEC.optionalFieldOf("preview_stack", ItemStack.EMPTY)
                    .forGetter(value -> value.preview),
                Codec.BOOL.optionalFieldOf("parallel_unaffected", false).forGetter(ItemAmount::parallelUnaffected),
                Codec.LONG.optionalFieldOf("min_amount", 0L).forGetter(value -> value.minAmount),
                Codec.LONG.optionalFieldOf("max_amount", 0L).forGetter(value -> value.maxAmount),
                Codec.BOOL.optionalFieldOf("ignore_output_check", false).forGetter(ItemAmount::ignoreOutputCheck))
            .apply(instance, ItemAmount::new));
    private final ItemStack prototype;
    private final long amount;
    private final double chance;
    private final String selector;
    private final String match;
    private final ItemStack preview;
    private final boolean parallelUnaffected;
    private final long minAmount;
    private final long maxAmount;
    private final boolean ignoreOutputCheck;

    private ItemAmount(ItemStack prototype, long amount, double chance, String selector, String match, ItemStack preview, boolean parallelUnaffected, long minAmount, long maxAmount, boolean ignoreOutputCheck) {
      if (prototype.isEmpty()
          || prototype.getCount() != 1
          || amount <= 0
          || !Double.isFinite(chance)
          || chance < 0
          || chance > 1
          || !("exact".equals(match) || "item".equals(match))
          || !preview.isEmpty() && preview.getCount() != 1
          || minAmount < 0
          || maxAmount < minAmount
          || maxAmount > 0 && amount != maxAmount) {
        throw new IllegalArgumentException("Expected one prototype and positive long amount");
      }
      this.prototype = prototype.copy();
      this.amount = amount;
      this.chance = chance;
      this.selector = ResourceSelector.require(selector);
      this.match = match;
      this.preview = preview.copy();
      this.parallelUnaffected = parallelUnaffected;
      this.minAmount = minAmount;
      this.maxAmount = maxAmount;
      this.ignoreOutputCheck = ignoreOutputCheck;
    }

    public String match() {
      return match;
    }

    public boolean ignoresComponents() {
      return match.equals("item");
    }

    public String selector() {
      return selector;
    }

    public long amount() {
      return amount;
    }

    public long minimumAmount() {
      return maxAmount == 0 ? amount : minAmount;
    }

    public double chance() {
      return chance;
    }

    public boolean parallelUnaffected() {
      return parallelUnaffected;
    }

    public boolean ignoreOutputCheck() {
      return ignoreOutputCheck;
    }

    public ItemStack prototype(HolderLookup.Provider registries) {
      return ItemStackSnapshots.detached(prototype, registries);
    }

    /**
     * Client display copy; it is not an execution prototype or mutable recipe
     * state.
     */
    public ItemStack displayStack() {
      return preview.isEmpty() ? prototype.copy() : preview.copy();
    }

    public ItemStack ingredientStack() {
      return prototype.copy();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof ItemAmount value
              && amount == value.amount
              && chance == value.chance
              && parallelUnaffected == value.parallelUnaffected
              && ignoreOutputCheck == value.ignoreOutputCheck
              && minAmount == value.minAmount
              && maxAmount == value.maxAmount
              && selector.equals(value.selector)
              && match.equals(value.match)
              && ItemStack.matches(prototype, value.prototype)
              && ItemStack.matches(preview, value.preview);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          prototype.getItem(),
          prototype.getComponents(),
          amount,
          chance == 0 ? 0.0D : chance,
          selector,
          match,
          preview.getItem(),
          preview.getComponents(),
          preview.getCount(),
          parallelUnaffected,
          ignoreOutputCheck,
          minAmount,
          maxAmount);
    }
  }

  private static final MapCodec<MachineRecipe> BASE = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          ResourceLocation.CODEC.fieldOf("machine").forGetter(MachineRecipe::machineId),
          Codec.INT.optionalFieldOf("priority", 0).forGetter(MachineRecipe::priority),
          Codec.INT.fieldOf("duration").forGetter(MachineRecipe::duration),
          RecipeResourceDefinitions.CODEC.forGetter(MachineRecipe::resourceData),
          Codec.STRING.fieldOf("failure_action").forGetter(value -> value.failureAction().serialized()),
          Codec.BOOL.fieldOf("parallelized").forGetter(MachineRecipe::parallelized),
          Codec.LONG.optionalFieldOf("random_seed", 0L).forGetter(MachineRecipe::randomSeed),
          SmartNumberRequirement.CODEC.listOf().optionalFieldOf("interface_inputs", List.of())
              .forGetter(MachineRecipe::interfaceInputs),
          RecipeSelectors.CODEC.optionalFieldOf("selectors", RecipeSelectors.NONE).forGetter(MachineRecipe::selectors),
          Extras.CODEC.forGetter(value -> value.extras)).apply(instance, MachineRecipe::new));
  public static final MapCodec<MachineRecipe> CODEC = new MapCodec<>() {
    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
      return MachineRecipeJson.fields().stream().map(ops::createString);
    }

    @Override
    public <T> DataResult<MachineRecipe> decode(DynamicOps<T> ops, MapLike<T> input) {
      try {
        var mapped = ops.createMap(input.entries());
        var source = ops.convertTo(JsonOps.INSTANCE, mapped);
        if (mapped instanceof CompoundTag tag) {
          MachineRecipeJson.restoreRowBooleans(source.getAsJsonObject(), tag);
        }
        if (input.get("parallelized") instanceof ByteTag flag && flag.getAsByte() >= 0 && flag.getAsByte() <= 1) {
          var resolved = source.getAsJsonObject().deepCopy();
          resolved.addProperty("parallelized", flag.getAsByte() == 1);
          source = resolved;
        }
        if (source.isJsonObject() && source.getAsJsonObject().has(MachineRecipeJson.KUBEJS_CHANGED_MARKER)) {
          var cleaned = source.getAsJsonObject().deepCopy();
          cleaned.remove(MachineRecipeJson.KUBEJS_CHANGED_MARKER);
          source = cleaned;
        }
        var effective = MachineRecipeJson.normalize(source);
        if (effective == source) {
          return BASE.decode(ops, input);
        }

        return BASE.codec().parse(ops, JsonOps.INSTANCE.convertTo(ops, effective));
      } catch (RuntimeException invalid) {
        return DataResult.error(() -> "Invalid machine recipe: " + invalid.getMessage());
      }
    }

    @Override
    public <T> RecordBuilder<T> encode(MachineRecipe value, DynamicOps<T> ops, RecordBuilder<T> prefix) {
      return BASE.encode(value, ops, prefix);
    }
  };
  public static final StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> STREAM_CODEC = StreamCodec
      .of(MachineRecipe::write, MachineRecipe::read);
  private final ResourceLocation machineId;
  private final int priority, duration;
  private final RecipeResources resourceData;
  private final Extras extras;
  private final RecipeFailureAction failureAction;
  private final boolean parallelized;
  private final long randomSeed;
  private final List<SmartNumberRequirement> interfaceInputs;
  private final RecipeSelectors selectors;

  private MachineRecipe(ResourceLocation machineId, int priority, int duration, RecipeResources resourceData, String failureAction, boolean parallelized, long randomSeed, List<SmartNumberRequirement> interfaceInputs, RecipeSelectors selectors, Extras extras) {
    if (duration <= 0) {
      throw new IllegalArgumentException("Nonpositive duration");
    }
    this.machineId = Objects.requireNonNull(machineId, "machine");
    this.priority = priority;
    this.duration = duration;
    this.resourceData = Objects.requireNonNull(resourceData, "resourceData");
    this.extras = Objects.requireNonNull(extras, "extras");
    this.failureAction = RecipeFailureAction.parse(failureAction);
    this.parallelized = parallelized;
    if (randomSeed < 0) {
      throw new IllegalArgumentException("Invalid random seed");
    }
    this.randomSeed = randomSeed;
    this.interfaceInputs = List.copyOf(interfaceInputs);
    this.selectors = Objects.requireNonNull(selectors, "selectors");
    extras.scriptOptions().validateRows(inputs().size(), outputs().size());
    if (interfaceInputs.size() > SmartInterfaceType.MAX_TYPES) {
      throw new IllegalArgumentException("Unsupported interface requirements/selectors for recipe schema");
    }
    validateAmounts(inputs());
    validateAmounts(outputs());
    if (inputs().stream().anyMatch(ItemAmount::ignoreOutputCheck)
        || fluidInputRequirements().stream().anyMatch(FluidRequirement::ignoreOutputCheck)
        || fluidPerTickInputs().stream().anyMatch(FluidRequirement::ignoreOutputCheck)
        || fluidPerTickOutputs().stream().anyMatch(FluidRequirement::ignoreOutputCheck)
        || chemicals().inputs().stream().anyMatch(ChemicalRequirement::ignoreOutputCheck)
        || chemicals().perTick().stream().anyMatch(ChemicalRequirement::ignoreOutputCheck)
        || chemicals().perTickOutputs().stream().anyMatch(ChemicalRequirement::ignoreOutputCheck)) {
      throw new IllegalArgumentException("Ignored output capacity is supported only by completion output rows");
    }
    if (resourceData.entries().stream()
        .noneMatch(entry -> entry.type() != RecipeResourceDefinitions.ENVIRONMENT && entry.hasInputs())
        && extras.ingredientArrays().inputs().stream().noneMatch(group -> !group.optional())
        && extras.triggeredResources().rows().stream().noneMatch(row -> row.io() == RecipeModifier.Io.INPUT)
        || !resourceData.hasOutputs()
            && extras.ingredientArrays().randomOutputs().isEmpty()
            && extras.triggeredResources().rows().stream().noneMatch(row -> row.io() == RecipeModifier.Io.OUTPUT)) {
      throw new IllegalArgumentException("A machine recipe requires resource input and output");
    }
    if (inputs().size() != 1 && inputs().stream().anyMatch(ItemAmount::ignoresComponents)
        || outputs().stream().anyMatch(ItemAmount::ignoresComponents)) {
      throw new IllegalArgumentException("Item-only matching requires one item input and exact outputs");
    }
  }

  public RecipeResources resourceData() {
    return resourceData;
  }

  public ItemPaymentData itemPayments() {
    return resourceData.get(RecipeResourceDefinitions.ITEM).payments();
  }

  private static void validateAmounts(List<ItemAmount> values) {
    if (values.size() > MachineRecipeJson.MAX_DEMANDS) {
      throw new IllegalArgumentException("Expected at most 256 item requirements");
    }
    var totals = new HashMap<StackIdentity, Long>();
    for (var value : values) {
      if (!value.ignoreOutputCheck) {
        totals.merge(new StackIdentity(value.prototype, value.selector), value.amount, Math::addExact);
      }
    }
  }

  private record StackIdentity(ItemStack prototype, String selector) {
    @Override
    public int hashCode() {
      return 31 * (31 * System.identityHashCode(prototype.getItem()) + prototype.getComponents().hashCode()) + selector
          .hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StackIdentity key
          && selector.equals(key.selector)
          && ItemStack.isSameItemSameComponents(prototype, key.prototype);
    }
  }

  public ResourceLocation machineId() {
    return machineId;
  }

  public int priority() {
    return priority;
  }

  public int duration() {
    return duration;
  }

  public long energyPerTick() {
    return resourceData.get(RecipeResourceDefinitions.ENERGY).inputPerTick();
  }

  public long energyOutputPerTick() {
    return resourceData.get(RecipeResourceDefinitions.ENERGY).outputPerTick();
  }

  public RecipeExecutionOptions executionOptions() {
    return extras.executionOptions();
  }

  public RecipeScriptOptions scriptOptions() {
    return extras.scriptOptions();
  }

  public IngredientArrayData ingredientArrays() {
    return extras.ingredientArrays();
  }

  public TriggeredResourceData triggeredResources() {
    return extras.triggeredResources();
  }

  public FluidStack fluidPerTick() {
    return resourceData.get(RecipeResourceDefinitions.FLUID).perTick();
  }

  public List<FluidRequirement> fluidPerTickInputs() {
    return resourceData.get(RecipeResourceDefinitions.FLUID).perTickInputs();
  }

  public List<FluidRequirement> fluidPerTickOutputs() {
    return resourceData.get(RecipeResourceDefinitions.FLUID).perTickOutputs();
  }

  public List<FluidStack> fluidInputs() {
    return fluidInputRequirements().stream().map(FluidRequirement::stack).toList();
  }

  public List<FluidStack> fluidOutputs() {
    return fluidOutputRequirements().stream().map(FluidRequirement::stack).toList();
  }

  /**
   * Startup capacity covers guaranteed outputs; sampled claims are planned at
   * completion.
   */
  List<FluidStack> guaranteedFluidOutputs() {
    return fluidOutputRequirements().stream().filter(output -> output.chance() == 1.0D && !output.ignoreOutputCheck())
        .map(FluidRequirement::stack).toList();
  }

  public List<FluidRequirement> fluidInputRequirements() {
    return resourceData.get(RecipeResourceDefinitions.FLUID).inputs();
  }

  public List<FluidRequirement> fluidOutputRequirements() {
    return resourceData.get(RecipeResourceDefinitions.FLUID).outputs();
  }

  public RecipeFailureAction failureAction() {
    return failureAction;
  }

  public boolean parallelized() {
    return parallelized;
  }

  public long randomSeed() {
    return randomSeed;
  }

  public List<ItemAmount> inputs() {
    return resourceData.get(RecipeResourceDefinitions.ITEM).inputs();
  }

  public List<ItemAmount> outputs() {
    return resourceData.get(RecipeResourceDefinitions.ITEM).outputs();
  }

  public List<SmartNumberRequirement> interfaceInputs() {
    return interfaceInputs;
  }

  public RecipeSelectors selectors() {
    return selectors;
  }

  public ChemicalRecipeData chemicals() {
    return resourceData.get(RecipeResourceDefinitions.CHEMICAL);
  }

  RecipeCandidate<ItemResourceKey> compile(ResourceLocation id, HolderLookup.Provider registries) {
    return new RecipeCandidate<>(
        id.toString(),
        priority,
        IntStream.range(0, inputs().size()).filter(row -> !itemPayments().isTool(row))
            .mapToObj(row -> inputs().get(row))
            .map(
                value -> new RecipeCandidate.Input<>(
                    inputKey(value, registries),
                    value.amount,
                    value.chance,
                    value.parallelUnaffected,
                    value.minimumAmount()))
            .toList(),
        outputs().stream().filter(value -> value.chance == 1.0D)
            .map(
                value -> new RecipeCandidate.Output<>(
                    ItemResourceKey.of(value.prototype, registries).withSelector(value.selector),
                    value.amount,
                    value.parallelUnaffected,
                    value.ignoreOutputCheck))
            .toList(),
        interfaceInputs);
  }

  private static ItemResourceKey inputKey(ItemAmount value, HolderLookup.Provider registries) {
    var key = ItemResourceKey.of(value.prototype, registries).withSelector(value.selector);

    return value.ignoresComponents() ? key.ignoringComponents() : key;
  }

  boolean sameDefinition(MachineRecipe other) {
    return machineId.equals(other.machineId)
        && priority == other.priority
        && duration == other.duration
        && resourceData.equals(other.resourceData)
        && extras.equals(other.extras)
        && failureAction == other.failureAction
        && parallelized == other.parallelized
        && randomSeed == other.randomSeed
        && interfaceInputs.equals(other.interfaceInputs)
        && selectors.equals(other.selectors);
  }

  private static void write(RegistryFriendlyByteBuf buffer, MachineRecipe value) {
    var ops = buffer.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    var encoded = CODEC.codec().encodeStart(ops, value).getOrThrow();
    if (!(encoded instanceof CompoundTag tag)) {
      throw new IllegalArgumentException("Machine recipe codec did not produce a compound");
    }
    buffer.writeVarInt(19);
    buffer.writeNbt(tag);
  }

  private static MachineRecipe read(RegistryFriendlyByteBuf buffer) {
    try {
      if (buffer.readVarInt() != 19) {
        throw new DecoderException("Unsupported machine recipe wire schema");
      }
      var encoded = buffer.readNbt();
      if (encoded == null) {
        throw new DecoderException("Missing machine recipe declaration");
      }
      var ops = buffer.registryAccess().createSerializationContext(NbtOps.INSTANCE);

      return CODEC.codec().parse(ops, encoded).getOrThrow();
    } catch (RuntimeException invalid) {
      throw new DecoderException("Invalid machine recipe packet", invalid);
    }
  }

  @Override
  public boolean matches(RecipeInput input, Level level) {
    return false;
  }

  @Override
  public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
    return ItemStack.EMPTY;
  }

  @Override
  public boolean canCraftInDimensions(int width, int height) {
    return false;
  }

  @Override
  public ItemStack getResultItem(HolderLookup.Provider registries) {
    if (outputs().isEmpty()) {
      return ItemStack.EMPTY;
    }
    var result = outputs().getFirst().prototype(registries);
    result.setCount((int) Math.min(outputs().getFirst().amount, result.getMaxStackSize()));
    return result;
  }

  @Override
  public boolean isSpecial() {
    return true;
  }

  @Override
  public boolean isIncomplete() {
    return false;
  }

  @Override
  public RecipeSerializer<?> getSerializer() {
    return MachineRecipeRegistries.ITEM_SERIALIZER.get();
  }

  @Override
  public RecipeType<?> getType() {
    return MachineRecipeRegistries.ITEM_TYPE.get();
  }

  public static final class Serializer implements RecipeSerializer<MachineRecipe> {
    @Override
    public MapCodec<MachineRecipe> codec() {
      return CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec() {
      return STREAM_CODEC;
    }
  }
}
