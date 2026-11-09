package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.util.minecraft.network.BlockPosCodecs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal display membership updates; never serializes component inventory or
 * run data.
 */
@ApiStatus.Internal
public final class MachineVisibilityPayloads {
  public static final int MAX_POSITIONS = 256;
  private static final CustomPacketPayload.Type<Update> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "machine_visibility"));
  private static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec
      .of(MachineVisibilityPayloads::write, MachineVisibilityPayloads::read);
  private static Consumer<Update> receiver = ignored -> {
  };

  private MachineVisibilityPayloads() {
  }

  public record Update(
      ResourceLocation dimension,
      BlockPos owner,
      long generation,
      long revision,
      boolean active,
      boolean removed,
      boolean membership,
      ChunkPos chunk,
      List<BlockPos> positions) implements CustomPacketPayload {
    public Update {
      Objects.requireNonNull(dimension, "dimension");
      owner = Objects.requireNonNull(owner, "owner").immutable();
      Objects.requireNonNull(chunk, "chunk");
      positions = positions.stream().map(BlockPos::immutable).toList();
      if (generation < 0
          || revision < 0
          || positions.size() > MAX_POSITIONS
          || removed && active
          || !membership && !positions.isEmpty()
          || membership && removed) {
        throw new IllegalArgumentException("Invalid machine visibility update");
      }
      for (var position : positions) {
        if (position.getX() >> 4 != chunk.x || position.getZ() >> 4 != chunk.z) {
          throw new IllegalArgumentException("Visibility position is outside its chunk");
        }
      }
    }

    @Override
    public Type<Update> type() {
      return TYPE;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1").playToClient(TYPE, CODEC, (payload, context) -> {
      context.enqueueWork(() -> receiver.accept(payload));
    });
  }

  public static void setClientReceiver(Consumer<Update> value) {
    receiver = Objects.requireNonNull(value, "receiver");
  }

  public static void state(ServerLevel level, BlockPos owner, long generation, long revision, boolean active, boolean removed) {
    var update = new Update(
        level.dimension().location(),
        owner,
        generation,
        revision,
        active,
        removed,
        false,
        new ChunkPos(owner),
        List.of());
    PacketDistributor.sendToPlayersInDimension(level, update);
  }

  public static void members(ServerLevel level, ServerPlayer player, BlockPos owner, long generation, long revision, boolean active, ChunkPos chunk, List<BlockPos> positions) {
    for (int start = 0; start < positions.size(); start += MAX_POSITIONS) {
      var update = new Update(
          level.dimension().location(),
          owner,
          generation,
          revision,
          active,
          false,
          true,
          chunk,
          positions.subList(start, Math.min(positions.size(), start + MAX_POSITIONS)));
      if (player == null) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk, update);
      } else {
        PacketDistributor.sendToPlayer(player, update);
      }
    }
  }

  private static void write(RegistryFriendlyByteBuf buffer, Update update) {
    buffer.writeResourceLocation(update.dimension());
    BlockPosCodecs.INT_VECTOR.encode(buffer, update.owner());
    buffer.writeLong(update.generation());
    buffer.writeLong(update.revision());
    buffer.writeBoolean(update.active());
    buffer.writeBoolean(update.removed());
    buffer.writeBoolean(update.membership());
    buffer.writeInt(update.chunk().x);
    buffer.writeInt(update.chunk().z);
    buffer.writeVarInt(update.positions().size());
    for (var position : update.positions()) {
      BlockPosCodecs.INT_VECTOR.encode(buffer, position);
    }
  }

  private static Update read(RegistryFriendlyByteBuf buffer) {
    var dimension = buffer.readResourceLocation();
    var owner = BlockPosCodecs.INT_VECTOR.decode(buffer);
    long generation = buffer.readLong();
    long revision = buffer.readLong();
    boolean active = buffer.readBoolean();
    boolean removed = buffer.readBoolean();
    boolean membership = buffer.readBoolean();
    var chunk = new ChunkPos(buffer.readInt(), buffer.readInt());
    int count = buffer.readVarInt();
    if (count < 0 || count > MAX_POSITIONS || count > buffer.readableBytes() / 12) {
      throw new IllegalArgumentException("Invalid visibility position count");
    }
    var positions = new ArrayList<BlockPos>(count);
    for (int index = 0; index < count; index++) {
      positions.add(BlockPosCodecs.INT_VECTOR.decode(buffer));
    }

    return new Update(dimension, owner, generation, revision, active, removed, membership, chunk, positions);
  }
}
