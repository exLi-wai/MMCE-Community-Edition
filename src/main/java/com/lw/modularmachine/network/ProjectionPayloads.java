package com.lw.modularmachine.network;

import com.lw.modularmachine.machine.assembly.AssemblyPattern;
import com.lw.modularmachine.machine.assembly.AssemblyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Request-scoped projection; no client-supplied coordinates or material
 * authority.
 */
public final class ProjectionPayloads {
  private static final CustomPacketPayload.Type<Snapshot> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "projection"));
  private static final CustomPacketPayload.Type<Stop> STOP = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "projection_stop"));

  public record Stop(long request) implements CustomPacketPayload {
    public Stop {
      if (request < 0) {
        throw new IllegalArgumentException("Invalid projection request");
      }
    }

    @Override
    public Type<Stop> type() {
      return STOP;
    }
  }
  private static Consumer<Snapshot> receiver = ignored -> {
  };
  public record Entry(BlockPos position, List<BlockState> states, List<String> properties) {
    public Entry {
      position = position.immutable();
      states = List.copyOf(states);
      properties = List.copyOf(properties);
      if (properties.size() > 64) {
        throw new IllegalArgumentException("Too many preview constraints");
      }
      if (states.size() > 256) {
        throw new IllegalArgumentException("Too many candidates");
      }
    }
    public boolean matches(BlockState actual) {
      for (var state : states) {
        if (state.getBlock() != actual.getBlock()) {
          continue;
        }
        boolean matches = true;
        for (String name : properties) {
          var property = state.getBlock().getStateDefinition().getProperty(name);
          if (property == null || !state.getValue(property).equals(actual.getValue(property))) {
            matches = false;
            break;
          }
        }
        if (matches) {
          return true;
        }
      }
      return false;
    }
  }
  public record Snapshot(
      long request,
      long epoch,
      ResourceLocation dimension,
      ResourceLocation machine,
      BlockPos origin,
      int start,
      int total,
      List<Entry> entries) implements CustomPacketPayload {
    public Snapshot {
      origin = origin.immutable();
      entries = List.copyOf(entries);
      if (request < 0
          || epoch < 0
          || start < -1
          || total < 0
          || entries.size() > 32
          || start >= 0 && start + entries.size() > total) {
        throw new IllegalArgumentException("Invalid projection chunk");
      }
    }
    @Override
    public Type<Snapshot> type() {
      return TYPE;
    }
  }
  private ProjectionPayloads() {
  }
  public static void setReceiver(Consumer<Snapshot> value) {
    receiver = Objects.requireNonNull(value);
  }
  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1").playToClient(
        TYPE,
        StreamCodec.of(ProjectionPayloads::write, ProjectionPayloads::read),
        (packet, context) -> context.enqueueWork(() -> receiver.accept(packet)));
    event.registrar("1").playToServer(
        STOP,
        StreamCodec
            .of((buffer, value) -> buffer.writeVarLong(value.request()), buffer -> new Stop(buffer.readVarLong())),
        (packet, context) -> context.enqueueWork(() -> {
          if (context.player() instanceof ServerPlayer player) {
            AssemblyService.stopProjection(player, packet.request());
          }
        }));
  }

  public static void stop(long request) {
    PacketDistributor.sendToServer(new Stop(request));
  }
  public static void begin(ServerPlayer player, long request, long epoch, ResourceLocation machine, BlockPos origin, int total) {
    PacketDistributor.sendToPlayer(
        player,
        new Snapshot(request, epoch, player.level().dimension().location(), machine, origin, 0, total, List.of()));
  }

  /** One bounded packet per call. The caller controls fair per-tick streaming. */
  public static int sendChunk(ServerPlayer player, AssemblyPattern pattern, long request, List<Entry> entries, int start) {
    int end = Math.min(entries.size(), start + 32);
    PacketDistributor.sendToPlayer(
        player,
        new Snapshot(
            request,
            pattern.epoch(),
            player.level().dimension().location(),
            pattern.machine(),
            pattern.origin(),
            start,
            entries.size(),
            entries.subList(start, end)));
    return end;
  }
  public static void clear(ServerPlayer player, long request) {
    PacketDistributor.sendToPlayer(
        player,
        new Snapshot(
            request,
            0,
            player.level().dimension().location(),
            ResourceLocation.fromNamespaceAndPath("mmcece", "none"),
            BlockPos.ZERO,
            -1,
            0,
            List.of()));
  }
  private static void write(RegistryFriendlyByteBuf b, Snapshot s) {
    b.writeVarLong(s.request());
    b.writeVarLong(s.epoch());
    b.writeResourceLocation(s.dimension());
    b.writeResourceLocation(s.machine());
    b.writeBlockPos(s.origin());
    b.writeInt(s.start());
    b.writeVarInt(s.total());
    b.writeVarInt(s.entries().size());
    for (var entry : s.entries()) {
      b.writeVarInt(entry.properties().size());
      for (String property : entry.properties()) {
        b.writeUtf(property, 64);
      }
      b.writeBlockPos(entry.position());
      b.writeVarInt(entry.states().size());
      for (var state : entry.states()) {
        b.writeVarInt(Block.getId(state));
      }
    }
  }
  private static Snapshot read(RegistryFriendlyByteBuf b) {
    long request = b.readVarLong(), epoch = b.readVarLong();
    var dimension = b.readResourceLocation();
    var machine = b.readResourceLocation();
    var origin = b.readBlockPos();
    int start = b.readInt(), total = b.readVarInt(), size = b.readVarInt();
    if (size < 0 || size > 32) {
      throw new IllegalArgumentException("Too many projection entries");
    }
    var entries = new ArrayList<Entry>();
    for (int i = 0; i < size; i++) {
      int constraints = b.readVarInt();
      if (constraints < 0 || constraints > 64) {
        throw new IllegalArgumentException("Invalid preview constraints");
      }
      var properties = new ArrayList<String>();
      for (int j = 0; j < constraints; j++) {
        properties.add(b.readUtf(64));
      }
      var pos = b.readBlockPos();
      int count = b.readVarInt();
      if (count < 0 || count > 256) {
        throw new IllegalArgumentException("Too many projection candidates");
      }
      var states = new ArrayList<BlockState>();
      for (int j = 0; j < count; j++) {
        states.add(Block.stateById(b.readVarInt()));
      }
      entries.add(new Entry(pos, states, properties));
    }
    return new Snapshot(request, epoch, dimension, machine, origin, start, total, entries);
  }
}
