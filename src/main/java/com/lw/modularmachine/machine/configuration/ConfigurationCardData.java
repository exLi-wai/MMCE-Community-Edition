package com.lw.modularmachine.machine.configuration;

import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.machine.recipe.InputGroup;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal whitelist-only item data. Compatibility metadata never changes a
 * target machine identity.
 */
@ApiStatus.Internal
public record ConfigurationCardData(
    int version,
    Kind kind,
    Optional<ResourceLocation> compatibilityMachine,
    List<Channel> channels,
    int number,
    String interfaceType,
    double interfaceValue) {
  public enum Kind implements StringRepresentable {
    FACTORY("factory"),
    PARALLEL("parallel"),
    ITEM_GROUP("item_group"),
    FLUID_GROUP("fluid_group"),
    SMART("smart");

    private final String name;

    Kind(String name) {
      this.name = name;
    }

    @Override
    public String getSerializedName() {
      return name;
    }
  }

  public record Channel(
      String id,
      Optional<ResourceLocation> lockedRecipe,
      boolean enabled,
      int priority,
      int parallelLimit) {
    static final Codec<Channel> CODEC = RecordCodecBuilder
        .create(
            instance -> instance
                .group(
                    Codec.STRING.fieldOf("id").forGetter(Channel::id),
                    ResourceLocation.CODEC.optionalFieldOf("locked_recipe").forGetter(Channel::lockedRecipe),
                    Codec.BOOL.fieldOf("enabled").forGetter(Channel::enabled),
                    Codec.INT.fieldOf("priority").forGetter(Channel::priority),
                    Codec.INT.fieldOf("parallel_limit").forGetter(Channel::parallelLimit))
                .apply(instance, Channel::new));

    public Channel {
      Objects.requireNonNull(lockedRecipe);
      new FactoryThreadPreset.Thread(id, lockedRecipe.orElse(null), enabled, priority, parallelLimit);
    }

    FactoryThreadPreset.Thread intent() {
      return new FactoryThreadPreset.Thread(id, lockedRecipe.orElse(null), enabled, priority, parallelLimit);
    }
  }

  public static final Codec<ConfigurationCardData> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              Codec.intRange(1, 1).fieldOf("version").forGetter(ConfigurationCardData::version),
              StringRepresentable.fromEnum(Kind::values).fieldOf("kind").forGetter(ConfigurationCardData::kind),
              ResourceLocation.CODEC.optionalFieldOf("compatibility_machine")
                  .forGetter(ConfigurationCardData::compatibilityMachine),
              Channel.CODEC.listOf().fieldOf("channels").forGetter(ConfigurationCardData::channels),
              Codec.INT.fieldOf("number").forGetter(ConfigurationCardData::number),
              Codec.STRING.fieldOf("interface_type").forGetter(ConfigurationCardData::interfaceType),
              Codec.DOUBLE.fieldOf("interface_value").forGetter(ConfigurationCardData::interfaceValue))
          .apply(instance, ConfigurationCardData::new));

  public static final StreamCodec<RegistryFriendlyByteBuf, ConfigurationCardData> STREAM_CODEC = StreamCodec
      .of(ConfigurationCardData::write, ConfigurationCardData::read);

  public ConfigurationCardData {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(compatibilityMachine);
    channels = List.copyOf(channels);
    Objects.requireNonNull(interfaceType);
    if (version != 1
        || channels.size() > FactoryThreadStateCodec.MAX_THREADS
        || interfaceType.length() > ResourceSelector.MAX_LENGTH
        || !Double.isFinite(interfaceValue)
        || compatibilityMachine.filter(id -> id.toString().length() > 256).isPresent()) {
      throw new IllegalArgumentException("Invalid configuration card data");
    }
    if (kind == Kind.FACTORY) {
      if (compatibilityMachine.isEmpty() || number != 0 || !interfaceType.isEmpty() || interfaceValue != 0) {
        throw new IllegalArgumentException("Invalid factory card fields");
      }
      new FactoryThreadPreset(channels.stream().map(Channel::intent).toList());
    } else if (!channels.isEmpty()) {
      throw new IllegalArgumentException("Unexpected channel configuration");
    }
    if (kind == Kind.SMART) {
      ResourceSelector.require(interfaceType);
      if (compatibilityMachine.isEmpty() || interfaceType.isEmpty() || number != 0) {
        throw new IllegalArgumentException("Invalid smart-interface card fields");
      }
    } else if (!interfaceType.isEmpty() || interfaceValue != 0) {
      throw new IllegalArgumentException("Unexpected interface configuration");
    }
    if (kind != Kind.FACTORY && kind != Kind.SMART && compatibilityMachine.isPresent()) {
      throw new IllegalArgumentException("Unexpected machine compatibility field");
    }
    if (kind == Kind.PARALLEL && number < 0) {
      throw new IllegalArgumentException("Negative parallel configuration");
    }
    if (kind == Kind.ITEM_GROUP || kind == Kind.FLUID_GROUP) {
      InputGroup.require(number);
    }
  }

  public static ConfigurationCardData factory(ResourceLocation machine, FactoryThreadPreset preset) {
    var channels = preset.threads().stream()
        .map(
            thread -> new Channel(
                thread.id(),
                Optional.ofNullable(thread.lockedRecipe()),
                thread.enabled(),
                thread.priority(),
                thread.parallelLimit()))
        .toList();
    return new ConfigurationCardData(1, Kind.FACTORY, Optional.of(machine), channels, 0, "", 0);
  }

  public static ConfigurationCardData number(Kind kind, int number) {
    return new ConfigurationCardData(1, kind, Optional.empty(), List.of(), number, "", 0);
  }

  public static ConfigurationCardData smart(ResourceLocation machine, String type, double value) {
    return new ConfigurationCardData(1, Kind.SMART, Optional.of(machine), List.of(), 0, type, value);
  }

  public FactoryThreadPreset factoryPreset() {
    if (kind != Kind.FACTORY) {
      throw new IllegalStateException("Not a factory configuration");
    }
    return new FactoryThreadPreset(channels.stream().map(Channel::intent).toList());
  }

  private static void write(RegistryFriendlyByteBuf buffer, ConfigurationCardData data) {
    buffer.writeVarInt(data.version());
    buffer.writeEnum(data.kind());
    buffer.writeBoolean(data.compatibilityMachine().isPresent());
    data.compatibilityMachine().ifPresent(buffer::writeResourceLocation);
    buffer.writeVarInt(data.channels().size());
    for (var channel : data.channels()) {
      buffer.writeUtf(channel.id(), 64);
      buffer.writeBoolean(channel.lockedRecipe().isPresent());
      channel.lockedRecipe().ifPresent(buffer::writeResourceLocation);
      buffer.writeBoolean(channel.enabled());
      buffer.writeInt(channel.priority());
      buffer.writeVarInt(channel.parallelLimit());
    }
    buffer.writeInt(data.number());
    buffer.writeUtf(data.interfaceType(), ResourceSelector.MAX_LENGTH);
    buffer.writeDouble(data.interfaceValue());
  }

  private static ConfigurationCardData read(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var kind = buffer.readEnum(Kind.class);
    Optional<ResourceLocation> machine = buffer.readBoolean() ? Optional.of(buffer.readResourceLocation()) : Optional
        .empty();
    int count = buffer.readVarInt();
    if (count < 0 || count > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Too many copied channels");
    }
    var channels = new ArrayList<Channel>();
    for (int index = 0; index < count; index++) {
      String id = buffer.readUtf(64);
      Optional<ResourceLocation> locked = buffer.readBoolean() ? Optional.of(buffer.readResourceLocation()) : Optional
          .empty();
      channels.add(new Channel(id, locked, buffer.readBoolean(), buffer.readInt(), buffer.readVarInt()));
    }
    return new ConfigurationCardData(
        version,
        kind,
        machine,
        channels,
        buffer.readInt(),
        buffer.readUtf(ResourceSelector.MAX_LENGTH),
        buffer.readDouble());
  }
}
