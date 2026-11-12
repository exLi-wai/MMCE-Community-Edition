package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.minecraft.state.BlockStateValues;
import com.lw.modularmachine.util.minecraft.network.BlockPosCodecs;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Bounded, display-only structure snapshots; never exposes live worlds or
 * production state.
 */
public final class MachineJeiPayloads {
  private static final CustomPacketPayload.Type<Snapshot> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "jei_structure"));
  private static Consumer<Snapshot> receiver = ignored -> {
  };
  public static final int CHUNK_PARTS = 32;
  private static final int PACKET_BYTE_TARGET = 48 * 1024;
  public static final int MAX_PARTS = 4_194_305;

  /** Template counts refer to the declaration stream after the fixed parts. */
  public record Segment(
      String id,
      int minimum,
      int maximum,
      BlockPos origin,
      BlockPos step,
      int head,
      int body,
      int tail) {
    public Segment {
      origin = origin.immutable();
      step = step.immutable();
      if (!id.matches("[a-zA-Z0-9_.-]{1,64}")
          || minimum < 0
          || maximum < minimum
          || step.equals(BlockPos.ZERO)
          || head < 0
          || body <= 0
          || tail < 0
          || (long) head + (long) maximum * body + tail >= MAX_PARTS) {
        throw new IllegalArgumentException("Invalid display segment");
      }
    }

    public int templates() {
      return Math.addExact(Math.addExact(head, body), tail);
    }
  }
  public record Part(BlockPos offset, List<BlockState> alternatives) {
    public Part {
      offset = offset.immutable();
      alternatives = List.copyOf(alternatives);
      if (alternatives.isEmpty()) {
        throw new IllegalArgumentException("Invalid preview alternatives");
      }
    }
  }
  public record Snapshot(
      long epoch,
      int machines,
      ResourceLocation machine,
      String translation,
      int color,
      int start,
      int total,
      int fixed,
      List<Segment> segments,
      List<Part> parts) implements CustomPacketPayload {
    public Snapshot {
      parts = List.copyOf(parts);
      segments = List.copyOf(segments);
      if (epoch < 0
          || machines < 0
          || machines > 1024
          || translation.length() > 512
          || color < 0
          || color > 0xFFFFFF
          || start < -2
          || total < 0
          || total > MAX_PARTS
          || fixed < 0
          || fixed > total
          || segments.size() > 64
          || start != 0 && !segments.isEmpty()
          || parts.size() > CHUNK_PARTS
          || start >= 0 && start + parts.size() > total) {
        throw new IllegalArgumentException("Invalid JEI snapshot");
      }
    }
    @Override
    public Type<Snapshot> type() {
      return TYPE;
    }
  }
  private MachineJeiPayloads() {
  }
  public static void setReceiver(Consumer<Snapshot> value) {
    receiver = Objects.requireNonNull(value);
  }
  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("4").playToClient(
        TYPE,
        StreamCodec.of(MachineJeiPayloads::write, MachineJeiPayloads::read),
        (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
  }
  private static void write(RegistryFriendlyByteBuf buffer, Snapshot value) {
    buffer.writeVarLong(value.epoch());
    buffer.writeVarInt(value.machines());
    buffer.writeResourceLocation(value.machine());
    buffer.writeUtf(value.translation(), 512);
    buffer.writeInt(value.color());
    buffer.writeInt(value.start());
    buffer.writeVarInt(value.total());
    buffer.writeVarInt(value.fixed());
    buffer.writeVarInt(value.segments().size());
    for (var segment : value.segments()) {
      buffer.writeUtf(segment.id(), 64);
      buffer.writeVarInt(segment.minimum());
      buffer.writeVarInt(segment.maximum());
      BlockPosCodecs.INT_VECTOR.encode(buffer, segment.origin());
      BlockPosCodecs.INT_VECTOR.encode(buffer, segment.step());
      buffer.writeVarInt(segment.head());
      buffer.writeVarInt(segment.body());
      buffer.writeVarInt(segment.tail());
    }
    buffer.writeVarInt(value.parts().size());
    for (var part : value.parts()) {
      buffer.writeBlockPos(part.offset());
      buffer.writeVarInt(part.alternatives().size());
      for (var state : part.alternatives()) {
        buffer.writeVarInt(Block.getId(state));
      }
    }
  }
  private static Snapshot read(RegistryFriendlyByteBuf buffer) {
    long epoch = buffer.readVarLong();
    int machines = buffer.readVarInt();
    var machine = buffer.readResourceLocation();
    String translation = buffer.readUtf(512);
    int color = buffer.readInt();
    int start = buffer.readInt();
    int total = buffer.readVarInt();
    int fixed = buffer.readVarInt();
    int segmentCount = buffer.readVarInt();
    if (segmentCount < 0 || segmentCount > 64) {
      throw new IllegalArgumentException("Too many display segments");
    }
    var segments = new ArrayList<Segment>();
    for (int i = 0; i < segmentCount; i++) {
      segments.add(
          new Segment(
              buffer.readUtf(64),
              buffer.readVarInt(),
              buffer.readVarInt(),
              BlockPosCodecs.INT_VECTOR.decode(buffer),
              BlockPosCodecs.INT_VECTOR.decode(buffer),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt()));
    }
    int count = buffer.readVarInt();
    if (count < 0 || count > CHUNK_PARTS) {
      throw new IllegalArgumentException("Too many preview parts");
    }
    var parts = new ArrayList<Part>();
    for (int i = 0; i < count; i++) {
      var offset = buffer.readBlockPos();
      int size = buffer.readVarInt();
      if (size < 1 || size > buffer.readableBytes()) {
        throw new IllegalArgumentException("Invalid preview alternative count");
      }
      var states = new ArrayList<BlockState>();
      for (int j = 0; j < size; j++) {
        states.add(Block.stateById(buffer.readVarInt()));
      }
      parts.add(new Part(offset, states));
    }
    return new Snapshot(epoch, machines, machine, translation, color, start, total, fixed, segments, parts);
  }

  @SubscribeEvent
  public static void sync(OnDatapackSyncEvent event) {
    var definitions = StructureService.definitionsSnapshot().values().stream()
        .sorted(Comparator.comparing(MachineDefinition::id)).toList();
    var packets = new ArrayList<Snapshot>();
    long epoch = StructureService.definitionEpoch();
    var marker = ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "catalog");
    packets.add(new Snapshot(epoch, definitions.size(), marker, "", 0xFFFFFF, -1, 0, 0, List.of(), List.of()));
    for (var definition : definitions) {
      var segments = definition.dynamicPatterns().stream()
          .map(
              segment -> new Segment(
                  segment.id(),
                  segment.minimum(),
                  segment.maximum(),
                  segment.startOffset(),
                  segment.step(),
                  segment.start().size(),
                  segment.body().size(),
                  segment.end().size()))
          .toList();
      var parts = new ArrayList<Part>();
      parts.add(new Part(BlockPos.ZERO, List.of(controllerState(definition))));
      for (var part : definition.declarationParts()) {
        var states = new LinkedHashSet<BlockState>(states(part.rule()));
        parts.add(new Part(part.offset(), new ArrayList<>(states)));
      }
      for (int start = 0; start < parts.size();) {
        int end = chunkEnd(parts, start);
        packets.add(
            new Snapshot(
                epoch,
                definitions.size(),
                definition.id(),
                definition.translationKey(),
                definition.color(),
                start,
                parts.size(),
                definition.parts().size() + 1,
                start == 0 ? segments : List.of(),
                parts.subList(start, end)));
        start = end;
      }
    }
    packets.add(new Snapshot(epoch, definitions.size(), marker, "", 0xFFFFFF, -2, 0, 0, List.of(), List.of()));
    List<ServerPlayer> players = event.getPlayer() == null ? event.getPlayerList().getPlayers() : List
        .of(event.getPlayer());
    for (var player : players) {
      for (var packet : packets) {
        PacketDistributor.sendToPlayer(player, packet);
      }
    }
  }

  /**
   * Oversized single entries use NeoForge's negotiated packet splitter, never
   * truncation.
   */
  private static int chunkEnd(List<Part> parts, int start) {
    int end = start;
    long bytes = 16 * 1024;
    while (end < parts.size() && end - start < CHUNK_PARTS) {
      long cost = 16L + (long) parts.get(end).alternatives().size() * 5;
      if (end > start && bytes + cost > PACKET_BYTE_TARGET) {
        break;
      }
      bytes += cost;
      end++;
    }
    return end;
  }
  private static List<BlockState> states(MachineDefinition.Rule rule) {
    if (rule.port() != null) {
      return Stream
          .concat(
              MachineRegistries.PORTS.values().stream().map(holder -> holder.get()),
              MachineRegistries.NETWORK_PORTS.values().stream().map(holder -> holder.get()))
          .filter(block -> block.kind() == rule.port()).map(Block::defaultBlockState).toList();
    }
    return rule.alternatives().stream()
        .map(alternative -> BlockStateValues.apply(alternative.block().defaultBlockState(), alternative.properties()))
        .toList();
  }

  private static BlockState controllerState(MachineDefinition definition) {
    if (definition.factoryOnly()) {
      return RegisteredMachineItems.factoryBlock(definition.id()).map(Block::defaultBlockState)
          .orElseGet(() -> MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    }
    return RegisteredMachineItems.controllerBlock(definition.id()).map(Block::defaultBlockState)
        .orElseGet(() -> MachineRegistries.CONTROLLER.get().defaultBlockState());
  }

}
