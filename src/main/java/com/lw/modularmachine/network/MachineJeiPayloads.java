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
import com.lw.modularmachine.api.client.preview.PreviewBlockData;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.lw.modularmachine.machine.structure.StructureModifierDefinition;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

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
  public record Part(BlockPos offset, List<BlockState> alternatives, List<PreviewBlockData> previewTags) {
    public Part(BlockPos offset, List<BlockState> alternatives) {
      this(offset, alternatives, new CompoundTag());
    }

    public Part(BlockPos offset, List<BlockState> alternatives, CompoundTag previewData) {
      this(offset, alternatives, Collections.nCopies(alternatives.size(), PreviewBlockData.of(previewData)));
    }

    public Part {
      offset = offset.immutable();
      alternatives = List.copyOf(alternatives);
      previewTags = List.copyOf(previewTags);
      if (alternatives.isEmpty()
          || alternatives.size() != previewTags.size()
          || previewTags.stream().mapToLong(PreviewBlockData::sizeInBytes).sum() > 4_194_304L) {
        throw new IllegalArgumentException("Invalid preview alternatives");
      }
    }

  }
  public record Snapshot(
      long epoch,
      int machines,
      ResourceLocation machine,
      String translation,
      String prefix,
      List<SmartInterfaceType> interfaces,
      List<ModifierDisplay> modifiers,
      int color,
      int start,
      int total,
      int fixed,
      List<Segment> segments,
      List<Part> parts) implements CustomPacketPayload {
    public Snapshot(long epoch, int machines, ResourceLocation machine, String translation, int color, int start, int total, int fixed, List<Segment> segments, List<Part> parts) {
      this(epoch, machines, machine, translation, "", List.of(), List.of(), color, start, total, fixed, segments,
          parts);
    }

    public Snapshot {
      parts = List.copyOf(parts);
      segments = List.copyOf(segments);
      interfaces = List.copyOf(interfaces);
      modifiers = List.copyOf(modifiers);
      if (epoch < 0
          || machines < 0
          || machines > 1024
          || translation.length() > 512
          || prefix.length() > 512
          || interfaces.size() > SmartInterfaceType.MAX_TYPES
          || modifiers.size() > StructureModifierDefinition.MAX_GROUPS
          || start != 0 && !modifiers.isEmpty()
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

  public record ModifierDisplay(String id, StructureModifierDefinition.Display display) {
    public ModifierDisplay {
      if (id == null || id.length() > 64 || display == null) {
        throw new IllegalArgumentException("Invalid modifier presentation");
      }
    }
  }
  private MachineJeiPayloads() {
  }
  public static void setReceiver(Consumer<Snapshot> value) {
    receiver = Objects.requireNonNull(value);
  }
  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("5").playToClient(
        TYPE,
        StreamCodec.of(MachineJeiPayloads::write, MachineJeiPayloads::read),
        (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
  }
  private static void write(RegistryFriendlyByteBuf buffer, Snapshot value) {
    buffer.writeVarLong(value.epoch());
    buffer.writeVarInt(value.machines());
    buffer.writeResourceLocation(value.machine());
    buffer.writeUtf(value.translation(), 512);
    buffer.writeUtf(value.prefix(), 512);
    buffer.writeVarInt(value.interfaces().size());
    for (var type : value.interfaces()) {
      SmartInterfacePayloads.writeType(buffer, type);
    }
    buffer.writeVarInt(value.modifiers().size());
    for (var modifier : value.modifiers()) {
      buffer.writeUtf(modifier.id(), 64);
      buffer.writeUtf(modifier.display().name(), 256);
      buffer.writeUtf(modifier.display().description(), 512);
      ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, modifier.display().stack());
    }
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
      var tags = part.previewTags();
      for (int index = 0; index < part.alternatives().size(); index++) {
        buffer.writeVarInt(Block.getId(part.alternatives().get(index)));
        buffer.writeNbt(tags.get(index).compound());
      }
    }
  }
  private static Snapshot read(RegistryFriendlyByteBuf buffer) {
    long epoch = buffer.readVarLong();
    int machines = buffer.readVarInt();
    var machine = buffer.readResourceLocation();
    String translation = buffer.readUtf(512);
    String prefix = buffer.readUtf(512);
    int interfaceCount = buffer.readVarInt();
    if (interfaceCount < 0 || interfaceCount > SmartInterfaceType.MAX_TYPES) {
      throw new IllegalArgumentException("Too many preview smart interface types");
    }
    var interfaces = new ArrayList<SmartInterfaceType>();
    for (int index = 0; index < interfaceCount; index++) {
      interfaces.add(SmartInterfacePayloads.readType(buffer));
    }
    int modifierCount = buffer.readVarInt();
    if (modifierCount < 0 || modifierCount > StructureModifierDefinition.MAX_GROUPS) {
      throw new IllegalArgumentException("Too many structure modifier presentations");
    }
    var modifiers = new ArrayList<ModifierDisplay>();
    for (int index = 0; index < modifierCount; index++) {
      modifiers.add(
          new ModifierDisplay(
              buffer.readUtf(64),
              new StructureModifierDefinition.Display(
                  buffer.readUtf(256),
                  buffer.readUtf(512),
                  ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer))));
    }
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
      var tags = new ArrayList<PreviewBlockData>();
      for (int j = 0; j < size; j++) {
        states.add(Block.stateById(buffer.readVarInt()));
        tags.add(PreviewBlockData.of(Objects.requireNonNull(buffer.readNbt(), "preview NBT")));
      }
      parts.add(new Part(offset, states, tags));
    }
    return new Snapshot(
        epoch,
        machines,
        machine,
        translation,
        prefix,
        interfaces,
        modifiers,
        color,
        start,
        total,
        fixed,
        segments,
        parts);
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
      var replacements = new HashMap<Integer, List<MachineDefinition.Part>>();
      for (var group : definition.structureModifiers()) {
        if (group.allowReplacement()) {
          for (int index = 0; index < group.partIndices().size(); index++) {
            replacements.computeIfAbsent(group.partIndices().get(index), ignored -> new ArrayList<>())
                .add(group.conditions().get(index));
          }
        }
      }
      int declarationIndex = 0;
      for (var part : definition.declarationParts()) {
        var candidates = new LinkedHashSet<PreviewCandidate>();
        addCandidates(candidates, part);
        for (var replacement : replacements.getOrDefault(declarationIndex++, List.of())) {
          addCandidates(candidates, replacement);
        }
        parts.add(
            new Part(
                part.offset(),
                candidates.stream().map(PreviewCandidate::state).toList(),
                candidates.stream().map(PreviewCandidate::data).toList()));
      }
      for (int start = 0; start < parts.size();) {
        int end = chunkEnd(parts, start);
        packets.add(
            new Snapshot(
                epoch,
                definitions.size(),
                definition.id(),
                definition.translationKey(),
                definition.prefix(),
                definition.interfaceTypes().values().stream().sorted(Comparator.comparing(SmartInterfaceType::id))
                    .toList(),
                start == 0 ? definition.structureModifiers().stream()
                    .map(group -> new ModifierDisplay(group.id(), group.display())).toList() : List.of(),
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

  private record PreviewCandidate(BlockState state, PreviewBlockData data) {
  }

  private static void addCandidates(Set<PreviewCandidate> candidates, MachineDefinition.Part part) {
    var data = part.scriptCondition() == null ? PreviewBlockData.EMPTY : PreviewBlockData
        .of(part.scriptCondition().preview());
    for (var state : states(part.rule())) {
      candidates.add(new PreviewCandidate(state, data));
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
      long cost = 16L + (long) parts.get(end).alternatives().size() * 5 + parts.get(end).previewTags().stream()
          .mapToLong(PreviewBlockData::sizeInBytes).sum();
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
