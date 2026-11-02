package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.lw.modularmachine.machine.smart.SmartNumberRequirement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
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

/**
 * Native data definition. This recipe cannot assemble through a crafting grid
 * or bypass machine admission.
 */
public final class MachineItemRecipe implements Recipe<RecipeInput> {
  public static final class ItemAmount {
    private static final Codec<ItemAmount> CODEC = RecordCodecBuilder.create(
        instance -> instance
            .group(
                ItemStack.SINGLE_ITEM_CODEC.fieldOf("stack").forGetter((ItemAmount value) -> value.prototype),
                Codec.LONG.fieldOf("amount").forGetter(ItemAmount::amount),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(ItemAmount::chance),
                Codec.STRING.optionalFieldOf("selector", "").forGetter(ItemAmount::selector))
            .apply(instance, ItemAmount::new));
    private final ItemStack prototype;
    private final long amount;
    private final double chance;
    private final String selector;

    private ItemAmount(ItemStack prototype, long amount, double chance, String selector) {
      if (prototype.isEmpty()
          || prototype.getCount() != 1
          || amount <= 0
          || !Double.isFinite(chance)
          || chance < 0
          || chance > 1) {
        throw new IllegalArgumentException("Expected one prototype and positive long amount");
      }
      this.prototype = prototype;
      this.amount = amount;
      this.chance = chance;
      this.selector = ResourceSelector.require(selector);
    }

    public String selector() {
      return selector;
    }

    public long amount() {
      return amount;
    }

    public double chance() {
      return chance;
    }

    public ItemStack prototype(HolderLookup.Provider registries) {
      return ItemStackSnapshots.detached(prototype, registries);
    }

    /**
     * Client display copy; it is not an execution prototype or mutable recipe
     * state.
     */
    public ItemStack displayStack() {
      return prototype.copy();
    }
  }

  private static final MapCodec<MachineItemRecipe> BASE = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          Codec.INT.fieldOf("schema_version").forGetter(MachineItemRecipe::schemaVersion),
          ResourceLocation.CODEC.fieldOf("machine").forGetter(MachineItemRecipe::machineId),
          Codec.INT.optionalFieldOf("priority", 0).forGetter(MachineItemRecipe::priority),
          Codec.INT.fieldOf("duration").forGetter(MachineItemRecipe::duration),
          Codec.LONG.optionalFieldOf("energy_per_tick", 0L).forGetter(MachineItemRecipe::energyPerTick),
          FluidStack.CODEC.optionalFieldOf("fluid_per_tick", FluidStack.EMPTY)
              .forGetter(MachineItemRecipe::fluidPerTick),
          FluidStack.CODEC.listOf().optionalFieldOf("fluid_inputs", List.of())
              .forGetter(MachineItemRecipe::fluidInputs),
          FluidStack.CODEC.listOf().optionalFieldOf("fluid_outputs", List.of())
              .forGetter(MachineItemRecipe::fluidOutputs),
          Codec.STRING.optionalFieldOf("failure_action", "still")
              .forGetter(value -> value.failureAction().serialized()),
          Codec.LONG.optionalFieldOf("random_seed", 0L).forGetter(MachineItemRecipe::randomSeed),
          ItemAmount.CODEC.listOf().fieldOf("inputs").forGetter(MachineItemRecipe::inputs),
          ItemAmount.CODEC.listOf().fieldOf("outputs").forGetter(MachineItemRecipe::outputs),
          SmartNumberRequirement.CODEC.listOf().optionalFieldOf("interface_inputs", List.of())
              .forGetter(MachineItemRecipe::interfaceInputs),
          RecipeSelectors.CODEC.optionalFieldOf("selectors", RecipeSelectors.NONE)
              .forGetter(MachineItemRecipe::selectors))
          .apply(instance, MachineItemRecipe::new));
  public static final MapCodec<MachineItemRecipe> CODEC = new MapCodec<>() {
    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
      return MachineRecipeJson.FIELDS.stream().map(ops::createString);
    }

    @Override
    public <T> DataResult<MachineItemRecipe> decode(DynamicOps<T> ops, MapLike<T> input) {
      try {
        var source = ops.convertTo(JsonOps.INSTANCE, ops.createMap(input.entries()));
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
    public <T> RecordBuilder<T> encode(MachineItemRecipe value, DynamicOps<T> ops, RecordBuilder<T> prefix) {
      return BASE.encode(value, ops, prefix);
    }
  };
  public static final StreamCodec<RegistryFriendlyByteBuf, MachineItemRecipe> STREAM_CODEC = StreamCodec
      .of(MachineItemRecipe::write, MachineItemRecipe::read);
  private final ResourceLocation machineId;
  private final int schemaVersion, priority, duration;
  private final long energyPerTick;
  private final FluidStack fluidPerTick;
  private final List<FluidStack> fluidInputs;
  private final List<FluidStack> fluidOutputs;
  private final RecipeFailureAction failureAction;
  private final long randomSeed;
  private final List<ItemAmount> inputs, outputs;
  private final List<SmartNumberRequirement> interfaceInputs;
  private final RecipeSelectors selectors;

  private MachineItemRecipe(int schema, ResourceLocation machineId, int priority, int duration, long energyPerTick, FluidStack fluidPerTick, List<FluidStack> fluidInputs, List<FluidStack> fluidOutputs, String failureAction, long randomSeed, List<ItemAmount> inputs, List<ItemAmount> outputs, List<SmartNumberRequirement> interfaceInputs, RecipeSelectors selectors) {
    if (schema != 2 || duration <= 0 || energyPerTick < 0) {
      throw new IllegalArgumentException("Unsupported schema or nonpositive duration");
    }
    schemaVersion = schema;
    this.machineId = Objects.requireNonNull(machineId, "machine");
    this.priority = priority;
    this.duration = duration;
    this.energyPerTick = energyPerTick;
    this.fluidPerTick = fluidPerTick.copy();
    this.fluidInputs = fluidInputs.stream().map(FluidStack::copy).toList();
    this.fluidOutputs = fluidOutputs.stream().map(FluidStack::copy).toList();
    this.failureAction = RecipeFailureAction.parse(failureAction);
    if (randomSeed < 0) {
      throw new IllegalArgumentException("Invalid random seed");
    }
    this.randomSeed = randomSeed;
    this.inputs = List.copyOf(inputs);
    this.outputs = List.copyOf(outputs);
    this.interfaceInputs = List.copyOf(interfaceInputs);
    this.selectors = Objects.requireNonNull(selectors, "selectors");
    if (interfaceInputs.size() > SmartInterfaceType.MAX_TYPES) {
      throw new IllegalArgumentException("Unsupported interface requirements/selectors for recipe schema");
    }
    validateAmounts(this.inputs);
    validateAmounts(this.outputs);
  }

  private static void validateAmounts(List<ItemAmount> values) {
    if (values.isEmpty() || values.size() > MachineRecipeJson.MAX_DEMANDS) {
      throw new IllegalArgumentException("Expected 1..256 item requirements");
    }
    var totals = new HashMap<StackIdentity, Long>();
    for (var value : values) {
      totals.merge(new StackIdentity(value.prototype, value.selector), value.amount, Math::addExact);
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

  public int schemaVersion() {
    return schemaVersion;
  }

  public int priority() {
    return priority;
  }

  public int duration() {
    return duration;
  }

  public long energyPerTick() {
    return energyPerTick;
  }

  public FluidStack fluidPerTick() {
    return fluidPerTick.copy();
  }

  public List<FluidStack> fluidInputs() {
    return fluidInputs.stream().map(FluidStack::copy).toList();
  }

  public List<FluidStack> fluidOutputs() {
    return fluidOutputs.stream().map(FluidStack::copy).toList();
  }

  public RecipeFailureAction failureAction() {
    return failureAction;
  }

  public long randomSeed() {
    return randomSeed;
  }

  public List<ItemAmount> inputs() {
    return inputs;
  }

  public List<ItemAmount> outputs() {
    return outputs;
  }

  public List<SmartNumberRequirement> interfaceInputs() {
    return interfaceInputs;
  }

  public RecipeSelectors selectors() {
    return selectors;
  }

  RecipeCandidate<ItemResourceKey> compile(ResourceLocation id, HolderLookup.Provider registries) {
    return new RecipeCandidate<>(
        id.toString(),
        priority,
        inputs.stream()
            .map(
                value -> new RecipeCandidate.Input<>(
                    ItemResourceKey.of(value.prototype, registries).withSelector(value.selector),
                    value.amount,
                    value.chance))
            .toList(),
        outputs.stream().filter(value -> value.chance == 1.0D)
            .map(
                value -> new RecipeCandidate.Output<>(
                    ItemResourceKey.of(value.prototype, registries).withSelector(value.selector),
                    value.amount))
            .toList(),
        interfaceInputs);
  }

  boolean sameDefinition(MachineItemRecipe other) {
    return schemaVersion == other.schemaVersion
        && machineId.equals(other.machineId)
        && priority == other.priority
        && duration == other.duration
        && energyPerTick == other.energyPerTick
        && FluidStack.matches(fluidPerTick, other.fluidPerTick)
        && sameFluids(fluidInputs, other.fluidInputs)
        && sameFluids(fluidOutputs, other.fluidOutputs)
        && failureAction == other.failureAction
        && randomSeed == other.randomSeed
        && interfaceInputs.equals(other.interfaceInputs)
        && selectors.equals(other.selectors)
        && sameAmounts(inputs, other.inputs)
        && sameAmounts(outputs, other.outputs);
  }

  private static boolean sameAmounts(List<ItemAmount> a, List<ItemAmount> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i).amount != b.get(i).amount
          || a.get(i).chance != b.get(i).chance
          || !a.get(i).selector.equals(b.get(i).selector)
          || !ItemStack.matches(a.get(i).prototype, b.get(i).prototype)) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameFluids(List<FluidStack> a, List<FluidStack> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!FluidStack.matches(a.get(i), b.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static void write(RegistryFriendlyByteBuf buffer, MachineItemRecipe value) {
    buffer.writeVarInt(9);
    buffer.writeVarInt(value.schemaVersion);
    buffer.writeResourceLocation(value.machineId);
    buffer.writeVarInt(value.priority);
    buffer.writeVarInt(value.duration);
    buffer.writeVarLong(value.energyPerTick);
    FluidStack.OPTIONAL_STREAM_CODEC.encode(buffer, value.fluidPerTick);
    buffer.writeVarInt(value.fluidInputs.size());
    for (var fluid : value.fluidInputs) {
      FluidStack.OPTIONAL_STREAM_CODEC.encode(buffer, fluid);
    }
    buffer.writeVarInt(value.fluidOutputs.size());
    for (var fluid : value.fluidOutputs) {
      FluidStack.OPTIONAL_STREAM_CODEC.encode(buffer, fluid);
    }
    buffer.writeVarInt(value.failureAction.ordinal());
    buffer.writeVarLong(value.randomSeed);
    writeAmounts(buffer, value.inputs);
    writeAmounts(buffer, value.outputs);
    buffer.writeVarInt(value.interfaceInputs.size());
    for (var requirement : value.interfaceInputs) {
      buffer.writeUtf(requirement.type(), ResourceSelector.MAX_LENGTH);
      buffer.writeDouble(requirement.minimum());
      buffer.writeDouble(requirement.maximum());
    }
    buffer.writeUtf(value.selectors.fluidInput(), ResourceSelector.MAX_LENGTH);
    buffer.writeUtf(value.selectors.fluidOutput(), ResourceSelector.MAX_LENGTH);
    buffer.writeUtf(value.selectors.energyInput(), ResourceSelector.MAX_LENGTH);
    buffer.writeUtf(value.selectors.fluidPerTick(), ResourceSelector.MAX_LENGTH);
  }

  private static void writeAmounts(RegistryFriendlyByteBuf buffer, List<ItemAmount> values) {
    buffer.writeVarInt(values.size());
    for (var value : values) {
      ItemStack.STREAM_CODEC.encode(buffer, value.prototype);
      buffer.writeVarLong(value.amount);
      buffer.writeDouble(value.chance);
      buffer.writeUtf(value.selector, ResourceSelector.MAX_LENGTH);
    }
  }

  private static MachineItemRecipe read(RegistryFriendlyByteBuf buffer) {
    try {
      int schema = buffer.readVarInt();
      if (schema != 9) {
        throw new DecoderException("Unsupported machine recipe wire schema");
      }
      int recipeSchema = buffer.readVarInt();
      var machine = buffer.readResourceLocation();
      int priority = buffer.readVarInt(), duration = buffer.readVarInt();
      long energyPerTick = buffer.readVarLong();
      var fluidPerTick = FluidStack.OPTIONAL_STREAM_CODEC.decode(buffer);
      var fluidInputs = readFluids(buffer);
      var fluidOutputs = readFluids(buffer);
      var failureAction = readFailureAction(buffer);
      long randomSeed = buffer.readVarLong();
      var inputs = readAmounts(buffer);
      var outputs = readAmounts(buffer);
      int interfaceCount = buffer.readVarInt();
      if (interfaceCount < 0 || interfaceCount > SmartInterfaceType.MAX_TYPES) {
        throw new DecoderException("Too many interface number requirements");
      }
      var interfaceInputs = new ArrayList<SmartNumberRequirement>();
      for (int index = 0; index < interfaceCount; index++) {
        interfaceInputs.add(
            new SmartNumberRequirement(
                buffer.readUtf(ResourceSelector.MAX_LENGTH),
                buffer.readDouble(),
                buffer.readDouble()));
      }
      var selectors = new RecipeSelectors(
          buffer.readUtf(ResourceSelector.MAX_LENGTH),
          buffer.readUtf(ResourceSelector.MAX_LENGTH),
          buffer.readUtf(ResourceSelector.MAX_LENGTH),
          buffer.readUtf(ResourceSelector.MAX_LENGTH));
      return new MachineItemRecipe(
          recipeSchema,
          machine,
          priority,
          duration,
          energyPerTick,
          fluidPerTick,
          fluidInputs,
          fluidOutputs,
          failureAction,
          randomSeed,
          inputs,
          outputs,
          interfaceInputs,
          selectors);
    } catch (IllegalArgumentException | ArithmeticException | IndexOutOfBoundsException invalid) {
      throw new DecoderException("Invalid machine recipe packet", invalid);
    }
  }

  private static List<FluidStack> readFluids(RegistryFriendlyByteBuf buffer) {
    int size = buffer.readVarInt();
    if (size < 0 || size > MachineRecipeJson.MAX_DEMANDS) {
      throw new DecoderException("Invalid machine recipe fluid input size");
    }
    var values = new ArrayList<FluidStack>(size);
    for (int i = 0; i < size; i++) {
      var fluid = FluidStack.OPTIONAL_STREAM_CODEC.decode(buffer);
      if (fluid.isEmpty()) {
        throw new DecoderException("Empty machine recipe fluid input");
      }
      values.add(fluid);
    }
    return List.copyOf(values);
  }

  private static String readFailureAction(RegistryFriendlyByteBuf buffer) {
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= RecipeFailureAction.values().length) {
      throw new DecoderException("Invalid recipe failure action");
    }
    return RecipeFailureAction.values()[ordinal].serialized();
  }

  private static List<ItemAmount> readAmounts(RegistryFriendlyByteBuf buffer) {
    int size = buffer.readVarInt();
    if (size < 1 || size > MachineRecipeJson.MAX_DEMANDS) {
      throw new DecoderException("Invalid machine recipe list size");
    }
    var values = new ArrayList<ItemAmount>(size);
    for (int i = 0; i < size; i++) {
      values.add(
          new ItemAmount(
              ItemStack.STREAM_CODEC.decode(buffer),
              buffer.readVarLong(),
              buffer.readDouble(),
              buffer.readUtf(ResourceSelector.MAX_LENGTH)));
    }
    return List.copyOf(values);
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
    var result = outputs.getFirst().prototype(registries);
    result.setCount((int) Math.min(outputs.getFirst().amount, result.getMaxStackSize()));
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

  public static final class Serializer implements RecipeSerializer<MachineItemRecipe> {
    @Override
    public MapCodec<MachineItemRecipe> codec() {
      return CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, MachineItemRecipe> streamCodec() {
      return STREAM_CODEC;
    }
  }
}
