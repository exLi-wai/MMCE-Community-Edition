package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.controller.MachineControllerApi;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.menu.MachineResourceMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Versioned, bounded controller state payloads for menus and observers. */
public final class MachinePayloads {
  public static final int PROTOCOL_VERSION = 3;
  public static final int MAX_SHORTAGES = 8;
  public static final int MAX_TEXT_LENGTH = 512;
  public static final ResourceLocation STATUS_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "controller_status");
  public static final CustomPacketPayload.Type<ControllerStatusPayload> STATUS_TYPE = new CustomPacketPayload.Type<>(
      STATUS_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, ControllerStatusPayload> STATUS_CODEC = StreamCodec
      .of(MachinePayloads::writeStatus, MachinePayloads::readStatus);
  public static final ResourceLocation RESOURCE_STATUS_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "resource_port_status");
  public static final CustomPacketPayload.Type<ResourcePortStatusPayload> RESOURCE_STATUS_TYPE = new CustomPacketPayload.Type<>(
      RESOURCE_STATUS_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, ResourcePortStatusPayload> RESOURCE_STATUS_CODEC = StreamCodec
      .of(MachinePayloads::writeResourceStatus, MachinePayloads::readResourceStatus);
  public static final ResourceLocation RESOURCE_ACTION_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "resource_port_action");
  public static final CustomPacketPayload.Type<ResourcePortActionPayload> RESOURCE_ACTION_TYPE = new CustomPacketPayload.Type<>(
      RESOURCE_ACTION_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, ResourcePortActionPayload> RESOURCE_ACTION_CODEC = StreamCodec
      .of(MachinePayloads::writeResourceAction, MachinePayloads::readResourceAction);
  public static final ResourceLocation FACTORY_STATUS_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "factory_status");
  public static final CustomPacketPayload.Type<FactoryStatusPayload> FACTORY_STATUS_TYPE = new CustomPacketPayload.Type<>(
      FACTORY_STATUS_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, FactoryStatusPayload> FACTORY_STATUS_CODEC = StreamCodec
      .of(MachinePayloads::writeFactoryStatus, MachinePayloads::readFactoryStatus);
  public static final ResourceLocation FACTORY_ACTION_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "factory_action");
  public static final CustomPacketPayload.Type<FactoryThreadActionPayload> FACTORY_ACTION_TYPE = new CustomPacketPayload.Type<>(
      FACTORY_ACTION_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, FactoryThreadActionPayload> FACTORY_ACTION_CODEC = StreamCodec
      .of(MachinePayloads::writeFactoryAction, MachinePayloads::readFactoryAction);
  public static final int MAX_FACTORY_THREADS = FactoryThreadStateCodec.MAX_THREADS;
  private static volatile Consumer<ControllerStatusPayload> clientReceiver = ignored -> {
  };
  private static volatile Consumer<ResourcePortStatusPayload> resourceClientReceiver = ignored -> {
  };
  private static volatile Consumer<FactoryStatusPayload> factoryClientReceiver = ignored -> {
  };

  private MachinePayloads() {
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToClient(STATUS_TYPE, STATUS_CODEC, (payload, context) -> {
      context.enqueueWork(() -> clientReceiver.accept(payload));
    });
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToClient(
        RESOURCE_STATUS_TYPE,
        RESOURCE_STATUS_CODEC,
        (payload, context) -> context.enqueueWork(() -> resourceClientReceiver.accept(payload)));
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToServer(
        RESOURCE_ACTION_TYPE,
        RESOURCE_ACTION_CODEC,
        (payload, context) -> context.enqueueWork(() -> handleResourceAction(payload, context)));
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToClient(
        FACTORY_STATUS_TYPE,
        FACTORY_STATUS_CODEC,
        (payload, context) -> context.enqueueWork(() -> factoryClientReceiver.accept(payload)));
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToServer(
        FACTORY_ACTION_TYPE,
        FACTORY_ACTION_CODEC,
        (payload, context) -> context.enqueueWork(() -> handleFactoryAction(payload, context)));
  }

  public static void setClientReceiver(Consumer<ControllerStatusPayload> receiver) {
    clientReceiver = receiver == null ? ignored -> {
    } : receiver;
  }

  public static void setResourceClientReceiver(Consumer<ResourcePortStatusPayload> receiver) {
    resourceClientReceiver = receiver == null ? ignored -> {
    } : receiver;
  }

  public static void setFactoryClientReceiver(Consumer<FactoryStatusPayload> receiver) {
    factoryClientReceiver = receiver == null ? ignored -> {
    } : receiver;
  }

  public static FactoryStatusPayload factoryStatus(FactoryControllerBlockEntity factory) {
    var threads = factory.scheduler().channels().stream().limit(MAX_FACTORY_THREADS)
        .map(thread -> FactoryStatusThread.from(thread, factory.run(thread.threadId()).orElse(null))).toList();
    return new FactoryStatusPayload(
        PROTOCOL_VERSION,
        factory.getBlockPos(),
        factory.machineId(),
        factory.scheduler().cursor(),
        factory.persistentStateValid(),
        factory.verificationState().name(),
        factory.verificationFailure().name(),
        ControllerMetrics.averageTickMicros(),
        ControllerMetrics.averageSearchMicros(),
        threads);
  }

  private static void handleResourceAction(ResourcePortActionPayload payload, IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)
        || !(player.containerMenu instanceof MachineResourceMenu menu)
        || !menu.stillValid(player)
        || !menu.port().getBlockPos().equals(payload.position())
        || menu.port().kind().resource() != PortKind.Resource.FLUID) {
      return;
    }
    FluidUtil.interactWithFluidHandler(player, payload.hand(), menu.port().fluidTank());
    PacketDistributor.sendToPlayer(player, resourceStatus(menu.port()));
  }

  private static void handleFactoryAction(FactoryThreadActionPayload payload, IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)
        || !(player.containerMenu instanceof FactoryControllerMenu menu)
        || !menu.stillValid(player)
        || !menu.factory().getBlockPos().equals(payload.position())) {
      return;
    }
    boolean changed = menu.factory().updateThread(
        payload.threadId(),
        payload.expectedRevision(),
        payload.action() == FactoryAction.SET_LOCKED_RECIPE,
        payload.lockedRecipe(),
        payload.action() == FactoryAction.SET_ENABLED ? payload.enabled() : null,
        payload.action() == FactoryAction.SET_PARALLEL_LIMIT ? payload.parallelLimit() : null,
        payload.action() == FactoryAction.SET_PARALLEL_LIMIT ? payload.upgradeRevision() : null);
    if (changed) {
      PacketDistributor.sendToPlayer(player, factoryStatus(menu.factory()));
    }
  }

  public static ResourcePortStatusPayload resourceStatus(MachinePortBlockEntity port) {
    var fluid = port.kind().resource() == PortKind.Resource.FLUID ? port.fluidTank().getFluid()
        .copy() : FluidStack.EMPTY;
    long amount = switch (port.kind().resource()) {
      case ITEM -> 0;
      case FLUID -> fluid.getAmount();
      case ENERGY -> port.energy().stored();
    };
    long capacity = switch (port.kind().resource()) {
      case ITEM -> 0;
      case FLUID -> port.fluidTank().getCapacity();
      case ENERGY -> port.energy().capacity();
    };
    return new ResourcePortStatusPayload(
        PROTOCOL_VERSION,
        port.getBlockPos(),
        port.kind(),
        port.tier().name(),
        amount,
        capacity,
        port.kind().resource() == PortKind.Resource.ENERGY ? port.energy().transferLimit() : 0,
        fluid);
  }

  public record ResourcePortStatusPayload(
      int version,
      BlockPos position,
      PortKind kind,
      String tier,
      long amount,
      long capacity,
      long transferLimit,
      FluidStack fluid) implements CustomPacketPayload {
    public ResourcePortStatusPayload {
      if (version != PROTOCOL_VERSION
          || kind == null
          || kind.resource() == PortKind.Resource.ITEM
          || tier == null
          || !tier.matches("[a-z_]{1,32}")
          || amount < 0
          || capacity <= 0
          || amount > capacity
          || transferLimit < 0
          || fluid == null
          || kind.resource() == PortKind.Resource.ENERGY && !fluid.isEmpty()
          || !fluid.isEmpty() && fluid.getAmount() != amount) {
        throw new IllegalArgumentException("Invalid resource port status payload");
      }
      position = position.immutable();
      fluid = fluid.copy();
    }

    @Override
    public FluidStack fluid() {
      return fluid.copy();
    }

    @Override
    public CustomPacketPayload.Type<ResourcePortStatusPayload> type() {
      return RESOURCE_STATUS_TYPE;
    }
  }

  public record ResourcePortActionPayload(int version, BlockPos position, InteractionHand hand)
      implements
        CustomPacketPayload {
    public ResourcePortActionPayload {
      if (version != PROTOCOL_VERSION || position == null || hand == null) {
        throw new IllegalArgumentException("Invalid resource port action payload");
      }
      position = position.immutable();
    }

    @Override
    public CustomPacketPayload.Type<ResourcePortActionPayload> type() {
      return RESOURCE_ACTION_TYPE;
    }
  }

  public record FactoryStatusPayload(
      int version,
      BlockPos position,
      ResourceLocation machineId,
      int schedulerCursor,
      boolean persistentStateValid,
      String structureState,
      String structureFailure,
      int averageTickMicros,
      int averageSearchMicros,
      List<FactoryStatusThread> threads) implements CustomPacketPayload {
    public FactoryStatusPayload {
      if (version != PROTOCOL_VERSION
          || machineId == null
          || schedulerCursor < 0
          || structureState == null
          || structureFailure == null
          || averageTickMicros < 0
          || averageSearchMicros < 0
          || threads == null
          || threads.size() > MAX_FACTORY_THREADS) {
        throw new IllegalArgumentException("Invalid factory status payload");
      }
      position = position.immutable();
      requireText(structureState);
      requireText(structureFailure);
      threads = List.copyOf(threads);
    }

    @Override
    public CustomPacketPayload.Type<FactoryStatusPayload> type() {
      return FACTORY_STATUS_TYPE;
    }
  }

  public record FactoryThreadActionPayload(
      int version,
      BlockPos position,
      String threadId,
      long expectedRevision,
      FactoryAction action,
      ResourceLocation lockedRecipe,
      boolean enabled,
      int parallelLimit,
      long upgradeRevision) implements CustomPacketPayload {
    public FactoryThreadActionPayload {
      if (version != PROTOCOL_VERSION
          || position == null
          || threadId == null
          || !threadId.matches("[a-zA-Z0-9_.-]{1,64}")
          || expectedRevision < 0
          || action == null) {
        throw new IllegalArgumentException("Invalid factory thread action payload");
      }
      position = position.immutable();
      if (parallelLimit < 1 || upgradeRevision < 0) {
        throw new IllegalArgumentException("Invalid factory parallel settings");
      }
    }

    @Override
    public CustomPacketPayload.Type<FactoryThreadActionPayload> type() {
      return FACTORY_ACTION_TYPE;
    }
  }

  public enum FactoryAction {
    SET_LOCKED_RECIPE,
    SET_ENABLED,
    SET_PARALLEL_LIMIT
  }

  public record FactoryStatusThread(
      String threadId,
      ResourceLocation machineId,
      ResourceLocation lockedRecipe,
      boolean enabled,
      int priority,
      int parallelLimit,
      long upgradeRevision,
      int speedNumerator,
      int speedDenominator,
      int energyNumerator,
      int energyDenominator,
      int fluidNumerator,
      int fluidDenominator,
      String state,
      String reason,
      long revision,
      UUID runId,
      String runState,
      int progress,
      int duration) {
    public FactoryStatusThread {
      if (threadId == null
          || !threadId.matches("[a-zA-Z0-9_.-]{1,64}")
          || machineId == null
          || priority < FactoryThreadState.MIN_PRIORITY
          || priority > FactoryThreadState.MAX_PRIORITY
          || parallelLimit < 1
          || speedNumerator < 1
          || speedDenominator < 1
          || energyNumerator < 1
          || energyDenominator < 1
          || fluidNumerator < 1
          || fluidDenominator < 1
          || upgradeRevision < 0
          || state == null
          || reason == null
          || revision < 0
          || runState == null
          || progress < 0
          || duration < 0
          || progress > duration
          || runId == null) {
        throw new IllegalArgumentException("Invalid factory status thread");
      }
    }

    private static FactoryStatusThread from(FactoryThreadState state, FrozenItemRun run) {
      return new FactoryStatusThread(
          state.threadId(),
          state.machineId(),
          state.lockedRecipe(),
          state.enabled(),
          state.priority(),
          state.parallelLimit(),
          state.upgradeRevision(),
          state.upgradeSnapshot().speedNumerator(),
          state.upgradeSnapshot().speedDenominator(),
          state.upgradeSnapshot().energyNumerator(),
          state.upgradeSnapshot().energyDenominator(),
          state.upgradeSnapshot().fluidNumerator(),
          state.upgradeSnapshot().fluidDenominator(),
          state.state().name(),
          state.reason().name(),
          state.revision(),
          run == null ? new UUID(0, 0) : run.runId(),
          run == null ? "" : run.state().name(),
          run == null ? 0 : run.progress(),
          run == null ? 0 : run.duration());
    }
  }

  public record ControllerStatusPayload(
      int version,
      BlockPos position,
      ResourceLocation machineId,
      long definitionEpoch,
      String structureState,
      String structureFailure,
      String front,
      int roll,
      boolean productionAllowed,
      long successfulTransfers,
      long bindingRebuilds,
      String recipeStatus,
      String recipeId,
      String runState,
      int progress,
      int duration,
      int averageTickMicros,
      int averageSearchMicros,
      List<Shortage> shortages) implements CustomPacketPayload {
    public ControllerStatusPayload {
      if (version != PROTOCOL_VERSION
          || definitionEpoch < 0
          || roll < 0
          || roll > 3
          || progress < 0
          || duration < 0
          || progress > duration
          || averageTickMicros < 0
          || averageSearchMicros < 0
          || shortages.size() > MAX_SHORTAGES) {
        throw new IllegalArgumentException("Invalid controller status payload");
      }
      position = position.immutable();
      shortages = List.copyOf(shortages);
      requireText(structureState);
      requireText(structureFailure);
      requireText(front);
      requireText(recipeStatus);
      requireText(recipeId);
      requireText(runState);
    }

    public static ControllerStatusPayload from(MachineControllerApi.Snapshot snapshot) {
      var run = snapshot.runningRun().orElse(null);
      var shortages = new ArrayList<Shortage>();
      snapshot.recipeInputDiagnostic().ifPresent(
          diagnostic -> diagnostic.shortages().forEach(
              shortage -> shortages
                  .add(new Shortage(shortage.key(), shortage.required(), shortage.available(), shortage.missing()))));
      return new ControllerStatusPayload(
          PROTOCOL_VERSION,
          snapshot.position(),
          snapshot.machineId(),
          snapshot.definitionEpoch(),
          snapshot.structureState().name(),
          snapshot.structureFailure().name(),
          snapshot.orientation().front().getSerializedName(),
          snapshot.orientation().roll(),
          snapshot.productionAllowed(),
          snapshot.successfulTransfers(),
          snapshot.bindingRebuilds(),
          snapshot.recipeRunStatus().name(),
          run == null ? "" : run.recipeId().toString(),
          run == null ? "" : run.state().name(),
          run == null ? 0 : run.progress(),
          run == null ? 0 : run.duration(),
          ControllerMetrics.averageTickMicros(),
          ControllerMetrics.averageSearchMicros(),
          shortages);
    }

    @Override
    public CustomPacketPayload.Type<ControllerStatusPayload> type() {
      return STATUS_TYPE;
    }
  }

  public record Shortage(String key, long required, long available, long missing) {
    public Shortage {
      requireText(key);
      if (required <= 0 || available < 0 || missing <= 0 || missing != required - Math.min(required, available)) {
        throw new IllegalArgumentException("Invalid shortage");
      }
    }
  }

  private static void writeStatus(RegistryFriendlyByteBuf buffer, ControllerStatusPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeResourceLocation(payload.machineId());
    buffer.writeVarLong(payload.definitionEpoch());
    writeText(buffer, payload.structureState());
    writeText(buffer, payload.structureFailure());
    writeText(buffer, payload.front());
    buffer.writeVarInt(payload.roll());
    buffer.writeBoolean(payload.productionAllowed());
    buffer.writeVarLong(payload.successfulTransfers());
    buffer.writeVarLong(payload.bindingRebuilds());
    writeText(buffer, payload.recipeStatus());
    writeText(buffer, payload.recipeId());
    writeText(buffer, payload.runState());
    buffer.writeVarInt(payload.progress());
    buffer.writeVarInt(payload.duration());
    buffer.writeVarInt(payload.averageTickMicros());
    buffer.writeVarInt(payload.averageSearchMicros());
    buffer.writeVarInt(payload.shortages().size());
    for (var shortage : payload.shortages()) {
      writeText(buffer, shortage.key());
      buffer.writeVarLong(shortage.required());
      buffer.writeVarLong(shortage.available());
      buffer.writeVarLong(shortage.missing());
    }
  }

  private static ControllerStatusPayload readStatus(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var machineId = buffer.readResourceLocation();
    long epoch = buffer.readVarLong();
    String structureState = readText(buffer);
    String structureFailure = readText(buffer);
    String front = readText(buffer);
    int roll = buffer.readVarInt();
    boolean productionAllowed = buffer.readBoolean();
    long successfulTransfers = buffer.readVarLong();
    long bindingRebuilds = buffer.readVarLong();
    String recipeStatus = readText(buffer);
    String recipeId = readText(buffer);
    String runState = readText(buffer);
    int progress = buffer.readVarInt();
    int duration = buffer.readVarInt();
    int averageTickMicros = buffer.readVarInt();
    int averageSearchMicros = buffer.readVarInt();
    int size = buffer.readVarInt();
    if (size < 0 || size > MAX_SHORTAGES) {
      throw new IllegalArgumentException("Too many status shortages");
    }
    var shortages = new ArrayList<Shortage>(size);
    for (int i = 0; i < size; i++) {
      shortages.add(new Shortage(readText(buffer), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong()));
    }
    return new ControllerStatusPayload(
        version,
        position,
        machineId,
        epoch,
        structureState,
        structureFailure,
        front,
        roll,
        productionAllowed,
        successfulTransfers,
        bindingRebuilds,
        recipeStatus,
        recipeId,
        runState,
        progress,
        duration,
        averageTickMicros,
        averageSearchMicros,
        shortages);
  }

  private static void writeResourceStatus(RegistryFriendlyByteBuf buffer, ResourcePortStatusPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeVarInt(payload.kind().ordinal());
    writeText(buffer, payload.tier());
    buffer.writeVarLong(payload.amount());
    buffer.writeVarLong(payload.capacity());
    buffer.writeVarLong(payload.transferLimit());
    FluidStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.fluid());
  }

  private static void writeFactoryStatus(RegistryFriendlyByteBuf buffer, FactoryStatusPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeResourceLocation(payload.machineId());
    buffer.writeVarInt(payload.schedulerCursor());
    buffer.writeBoolean(payload.persistentStateValid());
    writeText(buffer, payload.structureState());
    writeText(buffer, payload.structureFailure());
    buffer.writeVarInt(payload.averageTickMicros());
    buffer.writeVarInt(payload.averageSearchMicros());
    buffer.writeVarInt(payload.threads().size());
    for (var thread : payload.threads()) {
      writeText(buffer, thread.threadId());
      buffer.writeResourceLocation(thread.machineId());
      buffer.writeBoolean(thread.lockedRecipe() != null);
      if (thread.lockedRecipe() != null) {
        buffer.writeResourceLocation(thread.lockedRecipe());
      }
      buffer.writeBoolean(thread.enabled());
      buffer.writeVarInt(thread.priority());
      buffer.writeVarInt(thread.parallelLimit());
      buffer.writeVarLong(thread.upgradeRevision());
      buffer.writeVarInt(thread.speedNumerator());
      buffer.writeVarInt(thread.speedDenominator());
      buffer.writeVarInt(thread.energyNumerator());
      buffer.writeVarInt(thread.energyDenominator());
      buffer.writeVarInt(thread.fluidNumerator());
      buffer.writeVarInt(thread.fluidDenominator());
      writeText(buffer, thread.state());
      writeText(buffer, thread.reason());
      buffer.writeVarLong(thread.revision());
      buffer.writeUUID(thread.runId());
      writeText(buffer, thread.runState());
      buffer.writeVarInt(thread.progress());
      buffer.writeVarInt(thread.duration());
    }
  }

  private static void writeFactoryAction(RegistryFriendlyByteBuf buffer, FactoryThreadActionPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    writeText(buffer, payload.threadId());
    buffer.writeVarLong(payload.expectedRevision());
    buffer.writeVarInt(payload.action().ordinal());
    buffer.writeBoolean(payload.lockedRecipe() != null);
    if (payload.lockedRecipe() != null) {
      buffer.writeResourceLocation(payload.lockedRecipe());
    }
    buffer.writeBoolean(payload.enabled());
    buffer.writeVarInt(payload.parallelLimit());
    buffer.writeVarLong(payload.upgradeRevision());
  }

  private static FactoryThreadActionPayload readFactoryAction(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    String threadId = readText(buffer);
    long revision = buffer.readVarLong();
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= FactoryAction.values().length) {
      throw new IllegalArgumentException("Invalid factory action");
    }
    var locked = buffer.readBoolean() ? buffer.readResourceLocation() : null;
    return new FactoryThreadActionPayload(
        version,
        position,
        threadId,
        revision,
        FactoryAction.values()[ordinal],
        locked,
        buffer.readBoolean(),
        buffer.readVarInt(),
        buffer.readVarLong());
  }

  private static FactoryStatusPayload readFactoryStatus(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var machineId = buffer.readResourceLocation();
    int cursor = buffer.readVarInt();
    boolean valid = buffer.readBoolean();
    String structureState = readText(buffer);
    String structureFailure = readText(buffer);
    int averageTickMicros = buffer.readVarInt();
    int averageSearchMicros = buffer.readVarInt();
    int size = buffer.readVarInt();
    if (size < 0 || size > MAX_FACTORY_THREADS) {
      throw new IllegalArgumentException("Too many factory status threads");
    }
    var threads = new ArrayList<FactoryStatusThread>(size);
    for (int i = 0; i < size; i++) {
      var id = readText(buffer);
      var machine = buffer.readResourceLocation();
      var locked = buffer.readBoolean() ? buffer.readResourceLocation() : null;
      threads.add(
          new FactoryStatusThread(
              id,
              machine,
              locked,
              buffer.readBoolean(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarLong(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              readText(buffer),
              readText(buffer),
              buffer.readVarLong(),
              buffer.readUUID(),
              readText(buffer),
              buffer.readVarInt(),
              buffer.readVarInt()));
    }
    return new FactoryStatusPayload(
        version,
        position,
        machineId,
        cursor,
        valid,
        structureState,
        structureFailure,
        averageTickMicros,
        averageSearchMicros,
        threads);
  }

  private static ResourcePortStatusPayload readResourceStatus(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= PortKind.values().length) {
      throw new IllegalArgumentException("Invalid resource port kind");
    }
    return new ResourcePortStatusPayload(
        version,
        position,
        PortKind.values()[ordinal],
        readText(buffer),
        buffer.readVarLong(),
        buffer.readVarLong(),
        buffer.readVarLong(),
        FluidStack.OPTIONAL_STREAM_CODEC.decode(buffer));
  }

  private static void writeResourceAction(RegistryFriendlyByteBuf buffer, ResourcePortActionPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeVarInt(payload.hand().ordinal());
  }

  private static ResourcePortActionPayload readResourceAction(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= InteractionHand.values().length) {
      throw new IllegalArgumentException("Invalid resource action hand");
    }
    return new ResourcePortActionPayload(version, position, InteractionHand.values()[ordinal]);
  }

  private static void writeText(RegistryFriendlyByteBuf buffer, String value) {
    requireText(value);
    buffer.writeUtf(value, MAX_TEXT_LENGTH);
  }

  private static String readText(RegistryFriendlyByteBuf buffer) {
    return buffer.readUtf(MAX_TEXT_LENGTH);
  }

  private static void requireText(String value) {
    if (value == null || value.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("Invalid payload text");
    }
  }
}
