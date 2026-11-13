package com.lw.modularmachine.machine.network.pattern;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal stable same-dimension address carried by configuration cards and
 * mirrors.
 */
@ApiStatus.Internal
public record PatternProviderReference(ResourceLocation dimension, BlockPos position, UUID provider) {
  public static final Codec<PatternProviderReference> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              ResourceLocation.CODEC.fieldOf("dimension").forGetter(PatternProviderReference::dimension),
              BlockPos.CODEC.fieldOf("position").forGetter(PatternProviderReference::position),
              UUIDUtil.CODEC.fieldOf("provider").forGetter(PatternProviderReference::provider))
          .apply(instance, PatternProviderReference::new));

  public PatternProviderReference {
    Objects.requireNonNull(dimension);
    position = Objects.requireNonNull(position).immutable();
    Objects.requireNonNull(provider);
  }

  public CompoundTag save() {
    var tag = new CompoundTag();
    tag.putString("dimension", dimension.toString());
    tag.putLong("position", position.asLong());
    tag.putUUID("provider", provider);
    return tag;
  }

  public static PatternProviderReference load(CompoundTag tag) {
    if (!tag.contains("dimension", Tag.TAG_STRING)
        || !tag.contains("position", Tag.TAG_LONG)
        || !tag.hasUUID("provider")) {
      throw new IllegalArgumentException("Incomplete pattern provider reference");
    }
    return new PatternProviderReference(
        ResourceLocation.parse(tag.getString("dimension")),
        BlockPos.of(tag.getLong("position")),
        tag.getUUID("provider"));
  }
}
