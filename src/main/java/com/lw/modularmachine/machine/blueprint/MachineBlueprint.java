package com.lw.modularmachine.machine.blueprint;

import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * The saved component contains pose only. Machine identity belongs to the
 * registered item.
 */
public record MachineBlueprint(Direction front, int roll) {
  public record Binding(ResourceLocation machine, MachineOrientation orientation) {
  }
  public static final Codec<MachineBlueprint> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              Direction.CODEC.fieldOf("front").forGetter(MachineBlueprint::front),
              Codec.intRange(0, 3).fieldOf("roll").forGetter(MachineBlueprint::roll))
          .apply(instance, MachineBlueprint::new));
  public static final StreamCodec<RegistryFriendlyByteBuf, MachineBlueprint> STREAM_CODEC = StreamCodec
      .of((buffer, value) -> {
        buffer.writeUtf(value.front().getSerializedName(), 8);
        buffer.writeVarInt(value.roll());
      }, buffer -> new MachineBlueprint(Direction.byName(buffer.readUtf(8)), buffer.readVarInt()));
  public MachineBlueprint {
    Objects.requireNonNull(front, "front");
    MachineOrientation.of(front, roll);
  }
  public MachineOrientation orientation() {
    return MachineOrientation.of(front, roll);
  }
  public static MachineBlueprint of(MachineOrientation pose) {
    return new MachineBlueprint(pose.front(), pose.roll());
  }
}
