package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
import net.minecraft.world.level.block.state.properties.Property;
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
  public record Part(BlockPos offset, List<BlockState> alternatives) {
    public Part {
      offset = offset.immutable();
      alternatives = List.copyOf(alternatives);
      if (alternatives.isEmpty() || alternatives.size() > 512) {
        throw new IllegalArgumentException("Invalid preview alternatives");
      }
    }
  }
  public record Snapshot(
      long epoch,
      int machines,
      ResourceLocation machine,
      String translation,
      int start,
      int total,
      List<Part> parts) implements CustomPacketPayload {
    public Snapshot {
      parts = List.copyOf(parts);
      if (epoch < 0
          || machines < 0
          || machines > 1024
          || translation.length() > 512
          || start < -2
          || total < 0
          || total > 4097
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
    event.registrar("1").playToClient(
        TYPE,
        StreamCodec.of(MachineJeiPayloads::write, MachineJeiPayloads::read),
        (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
  }
  private static void write(RegistryFriendlyByteBuf buffer, Snapshot value) {
    buffer.writeVarLong(value.epoch());
    buffer.writeVarInt(value.machines());
    buffer.writeResourceLocation(value.machine());
    buffer.writeUtf(value.translation(), 512);
    buffer.writeInt(value.start());
    buffer.writeVarInt(value.total());
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
    int start = buffer.readInt();
    int total = buffer.readVarInt();
    int count = buffer.readVarInt();
    if (count < 0 || count > CHUNK_PARTS) {
      throw new IllegalArgumentException("Too many preview parts");
    }
    var parts = new ArrayList<Part>();
    for (int i = 0; i < count; i++) {
      var offset = buffer.readBlockPos();
      int size = buffer.readVarInt();
      if (size < 1 || size > 512) {
        throw new IllegalArgumentException("Too many preview alternatives");
      }
      var states = new ArrayList<BlockState>();
      for (int j = 0; j < size; j++) {
        states.add(Block.stateById(buffer.readVarInt()));
      }
      parts.add(new Part(offset, states));
    }
    return new Snapshot(epoch, machines, machine, translation, start, total, parts);
  }
  @SubscribeEvent
  public static void sync(OnDatapackSyncEvent event) {
    var definitions = StructureService.definitionsSnapshot().values().stream()
        .sorted(Comparator.comparing(MachineDefinition::id)).toList();
    var packets = new ArrayList<Snapshot>();
    long epoch = StructureService.definitionEpoch();
    var marker = ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "catalog");
    packets.add(new Snapshot(epoch, definitions.size(), marker, "", -1, 0, List.of()));
    for (var definition : definitions) {
      var parts = new ArrayList<Part>();
      parts.add(new Part(BlockPos.ZERO, List.of(MachineRegistries.CONTROLLER.get().defaultBlockState())));
      for (int index = 0; index < definition.parts().size(); index++) {
        var part = definition.parts().get(index);
        var states = new LinkedHashSet<BlockState>(states(part.rule()));
        parts.add(new Part(part.offset(), new ArrayList<>(states)));
      }
      for (int start = 0; start < parts.size(); start += CHUNK_PARTS) {
        packets.add(
            new Snapshot(
                epoch,
                definitions.size(),
                definition.id(),
                definition.translationKey(),
                start,
                parts.size(),
                parts.subList(start, Math.min(parts.size(), start + CHUNK_PARTS))));
      }
    }
    packets.add(new Snapshot(epoch, definitions.size(), marker, "", -2, 0, List.of()));
    List<ServerPlayer> players = event.getPlayer() == null ? event.getPlayerList().getPlayers() : List
        .of(event.getPlayer());
    for (var player : players) {
      for (var packet : packets) {
        PacketDistributor.sendToPlayer(player, packet);
      }
    }
  }
  private static List<BlockState> states(MachineDefinition.Rule rule) {
    if (rule.port() != null) {
      return MachineRegistries.PORTS.values().stream().map(holder -> holder.get())
          .filter(block -> block.kind() == rule.port()).map(Block::defaultBlockState).toList();
    }
    return rule.alternatives().stream().map(alternative -> {
      var state = alternative.block().defaultBlockState();
      for (var property : alternative.properties().entrySet()) {
        state = property(state, property.getKey(), property.getValue());
      }
      return state;
    }).toList();
  }
  private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, Comparable<?> value) {
    return state.setValue(property, property.getValueClass().cast(value));
  }
}
