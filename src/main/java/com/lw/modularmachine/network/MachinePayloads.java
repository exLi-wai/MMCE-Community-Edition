package com.lw.modularmachine.network;

import com.lw.modularmachine.api.component.ResourceType;
import static com.lw.modularmachine.network.MachinePayloadText.requireText;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.controller.MachineControllerApi;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Versioned, bounded controller state payloads for menus and observers. */
public final class MachinePayloads {
  public static final int PROTOCOL_VERSION = 3;
  public static final int RESOURCE_PROTOCOL_VERSION = 4;
  public static final int MAX_SHORTAGES = 8;
  public static final int MAX_TEXT_LENGTH = 512;
  public static final ResourceLocation STATUS_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "controller_status");
  public static final CustomPacketPayload.Type<ControllerStatusPayload> STATUS_TYPE = new CustomPacketPayload.Type<>(
      STATUS_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, ControllerStatusPayload> STATUS_CODEC = StreamCodec
      .of(ControllerPayloadCodec::writeStatus, ControllerPayloadCodec::readStatus);
  public static final ResourceLocation RESOURCE_STATUS_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "resource_port_status");
  public static final CustomPacketPayload.Type<ResourcePortStatusPayload> RESOURCE_STATUS_TYPE = new CustomPacketPayload.Type<>(
      RESOURCE_STATUS_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, ResourcePortStatusPayload> RESOURCE_STATUS_CODEC = StreamCodec
      .of(ResourcePortPayloadCodec::writeResourceStatus, ResourcePortPayloadCodec::readResourceStatus);
  public static final ResourceLocation RESOURCE_ACTION_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "resource_port_action");
  public static final CustomPacketPayload.Type<ResourcePortActionPayload> RESOURCE_ACTION_TYPE = new CustomPacketPayload.Type<>(
      RESOURCE_ACTION_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, ResourcePortActionPayload> RESOURCE_ACTION_CODEC = StreamCodec
      .of(ResourcePortPayloadCodec::writeResourceAction, ResourcePortPayloadCodec::readResourceAction);
  public static final ResourceLocation FACTORY_STATUS_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "factory_status");
  public static final CustomPacketPayload.Type<FactoryStatusPayload> FACTORY_STATUS_TYPE = new CustomPacketPayload.Type<>(
      FACTORY_STATUS_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, FactoryStatusPayload> FACTORY_STATUS_CODEC = StreamCodec
      .of(FactoryPayloadCodec::writeFactoryStatus, FactoryPayloadCodec::readFactoryStatus);
  public static final ResourceLocation FACTORY_ACTION_ID = ResourceLocation
      .fromNamespaceAndPath(ModularMachine.MODID, "factory_action");
  public static final CustomPacketPayload.Type<FactoryThreadActionPayload> FACTORY_ACTION_TYPE = new CustomPacketPayload.Type<>(
      FACTORY_ACTION_ID);
  public static final StreamCodec<RegistryFriendlyByteBuf, FactoryThreadActionPayload> FACTORY_ACTION_CODEC = StreamCodec
      .of(FactoryPayloadCodec::writeFactoryAction, FactoryPayloadCodec::readFactoryAction);
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
    event.registrar(String.valueOf(RESOURCE_PROTOCOL_VERSION)).playToClient(
        RESOURCE_STATUS_TYPE,
        RESOURCE_STATUS_CODEC,
        (payload, context) -> context.enqueueWork(() -> resourceClientReceiver.accept(payload)));
    event.registrar(String.valueOf(RESOURCE_PROTOCOL_VERSION)).playToServer(
        RESOURCE_ACTION_TYPE,
        RESOURCE_ACTION_CODEC,
        (payload, context) -> context.enqueueWork(() -> MachinePayloadActions.handleResourceAction(payload, context)));
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToClient(
        FACTORY_STATUS_TYPE,
        FACTORY_STATUS_CODEC,
        (payload, context) -> context.enqueueWork(() -> factoryClientReceiver.accept(payload)));
    event.registrar(String.valueOf(PROTOCOL_VERSION)).playToServer(
        FACTORY_ACTION_TYPE,
        FACTORY_ACTION_CODEC,
        (payload, context) -> context.enqueueWork(() -> MachinePayloadActions.handleFactoryAction(payload, context)));
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
    return MachineStatusSnapshots.factoryStatus(factory);
  }

  public static ResourcePortStatusPayload resourceStatus(MachinePortBlockEntity port) {
    return MachineStatusSnapshots.resourceStatus(port);
  }

  public record ResourcePortStatusPayload(
      int version,
      BlockPos position,
      PortKind kind,
      String tier,
      long amount,
      long capacity,
      long transferLimit,
      FluidStack fluid,
      ChemicalStackData chemical) implements CustomPacketPayload {
    public ResourcePortStatusPayload {
      if (version != RESOURCE_PROTOCOL_VERSION
          || position == null
          || kind == null
          || kind.resource() == ResourceType.ITEM
          || tier == null
          || !tier.matches("[a-z_]{1,32}")
          || amount < 0
          || capacity <= 0
          || amount > capacity && kind.resource() != ResourceType.CHEMICAL
          || transferLimit < 0
          || fluid == null
          || chemical == null
          || kind.resource() != ResourceType.FLUID && !fluid.isEmpty()
          || kind.resource() == ResourceType.FLUID && fluid.getAmount() != amount
          || kind.resource() != ResourceType.CHEMICAL && !chemical.isEmpty()
          || kind.resource() == ResourceType.CHEMICAL && chemical.amount() != amount) {
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
      if (version != RESOURCE_PROTOCOL_VERSION || position == null || hand == null) {
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

}
