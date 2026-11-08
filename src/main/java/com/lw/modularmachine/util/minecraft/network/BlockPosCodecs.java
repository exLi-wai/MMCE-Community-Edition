package com.lw.modularmachine.util.minecraft.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal vector encodings; choose explicitly instead of replacing packed
 * world-position codecs.
 */
@ApiStatus.Internal
public final class BlockPosCodecs {
  /** Three signed, full-width integers in x/y/z order: exactly 12 bytes. */
  public static final StreamCodec<ByteBuf, BlockPos> INT_VECTOR = StreamCodec.composite(
      ByteBufCodecs.INT,
      BlockPos::getX,
      ByteBufCodecs.INT,
      BlockPos::getY,
      ByteBufCodecs.INT,
      BlockPos::getZ,
      BlockPos::new);

  private BlockPosCodecs() {
  }
}
