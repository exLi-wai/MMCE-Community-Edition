package com.lw.modularmachine.machine.configuration;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.machine.network.pattern.PatternProviderReference;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.item.ConfigurationCardItem;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.configuration.ConfigurationCardData.Kind;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineDataComponents;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal normal-interaction configuration transfer; never copies a block
 * entity's saved state.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = ModularMachine.MODID)
public final class ConfigurationCardService {
  private record OwnerSelection(
      ResourceKey<Level> dimension,
      BlockPos position,
      ResourceLocation machine,
      WeakReference<StructureService.StructureSubject> identity,
      long expires) {
  }
  private record InterfaceSelection(SmartInterfaceBlockEntity.Entry entry, String failure) {
  }
  private static final Map<UUID, OwnerSelection> OWNERS = new HashMap<>();

  private ConfigurationCardService() {
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
    if (event.isCanceled() || !(event.getItemStack().getItem() instanceof ConfigurationCardItem)) {
      return;
    }
    if (event.getUseBlock().isFalse() || event.getUseItem().isFalse()) {
      event.setCanceled(true);
      event.setCancellationResult(InteractionResult.FAIL);
      return;
    }
    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    if (event.getEntity() instanceof ServerPlayer player) {
      interact(player, event.getHand(), event.getPos());
    }
  }

  private static boolean editable(ServerPlayer player) {
    return player.isAlive()
        && !player.isSpectator()
        && player.mayBuild()
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !RecipeLifecyclePublisher.isDispatching()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  private static void interact(ServerPlayer player, InteractionHand hand, BlockPos position) {
    var level = player.serverLevel();
    ServerThreadGuard.require(level, "Configuration card interaction");
    if (hand != InteractionHand.MAIN_HAND) {
      message(player, "main_hand");
      return;
    }
    if (!(player.getMainHandItem().getItem() instanceof ConfigurationCardItem)
        || !editable(player)
        || !level.hasChunkAt(position)
        || !level.mayInteract(player, position)
        || !player.canInteractWithBlock(position, 0)) {
      message(player, "unavailable");
      return;
    }
    var target = level.getBlockEntity(position);
    if (target == null || target.isRemoved() || target.getLevel() != level) {
      message(player, "unsupported");
      return;
    }
    var saved = player.getMainHandItem().get(MachineDataComponents.CONFIGURATION_CARD.get());
    if (player.isShiftKeyDown()) {
      if (target instanceof StructureService.StructureSubject owner) {
        selectOwner(player, owner);
        if (!(target instanceof FactoryControllerBlockEntity) || saved != null && saved.kind() == Kind.SMART) {
          message(player, "owner_selected", position.toShortString());
          return;
        }
      }
      copy(player, target);
    } else if (saved == null) {
      message(player, "empty");
    } else {
      paste(player, target, saved);
    }
  }

  private static void selectOwner(ServerPlayer player, StructureService.StructureSubject owner) {
    OWNERS.put(
        player.getUUID(),
        new OwnerSelection(
            player.serverLevel().dimension(),
            owner.structurePosition().immutable(),
            owner.structureMachineId(),
            new WeakReference<>(owner),
            player.serverLevel().getGameTime() + 6000));
  }

  private static void copy(ServerPlayer player, BlockEntity target) {
    ConfigurationCardData copied;
    if (target instanceof FactoryControllerBlockEntity factory) {
      var preset = factory.copyThreadConfiguration();
      if (preset.isEmpty()) {
        message(player, "unavailable");
        return;
      }
      copied = ConfigurationCardData.factory(factory.machineId(), preset.get());
    } else if (target instanceof PatternProviderBlockEntity provider && provider.canMutate()) {
      copied = ConfigurationCardData.pattern(
          new PatternProviderReference(
              player.serverLevel().dimension().location(),
              provider.getBlockPos(),
              provider.providerId()));
    } else if (target instanceof ParallelControllerBlockEntity parallel && parallel.schemaSupported()) {
      copied = ConfigurationCardData.number(Kind.PARALLEL, parallel.parallelism());
    } else if (target instanceof MachinePortBlockEntity port && port.schemaSupported() && port.canGroupInput()) {
      copied = ConfigurationCardData.number(
          port.kind().resource() == PortKind.Resource.ITEM ? Kind.ITEM_GROUP : Kind.FLUID_GROUP,
          port.inputGroup());
    } else if (target instanceof SmartInterfaceBlockEntity smart && smart.schemaSupported()) {
      var selected = interfaceSelection(player, smart);
      if (selected.entry() == null) {
        message(player, selected.failure());
        return;
      }
      var binding = selected.entry().binding();
      copied = ConfigurationCardData.smart(binding.machine(), binding.type(), binding.value());
    } else {
      message(player, "unsupported");
      return;
    }
    player.getMainHandItem().set(MachineDataComponents.CONFIGURATION_CARD.get(), copied);
    syncCard(player);
    if (copied.kind() == Kind.FACTORY) {
      message(player, "saved_factory", target.getBlockPos().toShortString());
    } else {
      message(player, "saved", typeName(copied.kind()));
    }
  }

  private static void paste(ServerPlayer player, BlockEntity target, ConfigurationCardData saved) {
    boolean applied;
    if (saved.kind() == Kind.FACTORY && target instanceof FactoryControllerBlockEntity factory) {
      var result = factory.pasteThreadConfiguration(
          saved.compatibilityMachine().orElseThrow(),
          factory.configurationRevision(),
          saved.factoryPreset());
      if (result == FactoryControllerBlockEntity.ConfigurationResult.BUSY) {
        message(player, "busy");
        return;
      }
      applied = result == FactoryControllerBlockEntity.ConfigurationResult.SUCCESS
          || result == FactoryControllerBlockEntity.ConfigurationResult.NO_CHANGE;
    } else if (saved.kind() == Kind.PATTERN_PROVIDER && target instanceof PatternMirrorBlockEntity mirror) {
      var reference = saved.patternTarget().orElseThrow();
      if (!reference.dimension().equals(player.serverLevel().dimension().location())) {
        message(player, "cross_dimension");
        return;
      }
      applied = mirror.bind(player, mirror.bindingRevision(), reference);
    } else if (saved.kind() == Kind.PARALLEL && target instanceof ParallelControllerBlockEntity parallel) {
      if (saved.number() > parallel.maximum()) {
        message(player, "out_of_range");
        return;
      }
      applied = parallel.update(player, parallel.revision(), saved.number());
    } else if ((saved.kind() == Kind.ITEM_GROUP || saved.kind() == Kind.FLUID_GROUP)
        && target instanceof MachinePortBlockEntity port
        && port.canGroupInput()) {
      boolean item = port.kind().resource() == PortKind.Resource.ITEM;
      if (item != (saved.kind() == Kind.ITEM_GROUP)) {
        message(player, "incompatible");
        return;
      }
      applied = port.configureInputGroup(saved.number(), port.groupRevision());
    } else if (saved.kind() == Kind.SMART
        && target instanceof SmartInterfaceBlockEntity smart
        && smart.schemaSupported()) {
      var selected = interfaceSelection(player, smart);
      if (selected.entry() == null) {
        message(player, selected.failure());
        return;
      }
      var entry = selected.entry();
      var binding = entry.binding();
      if (!saved.compatibilityMachine().orElseThrow().equals(binding.machine())
          || !saved.interfaceType().equals(binding.type())) {
        message(player, "incompatible");
        return;
      }
      if (!entry.specification().accepts(saved.interfaceValue())) {
        message(player, "out_of_range");
        return;
      }
      applied = smart.update(player, binding.controller(), binding.type(), binding.revision(), saved.interfaceValue());
    } else {
      message(player, "incompatible");
      return;
    }
    message(player, applied ? "applied" : "incompatible", typeName(saved.kind()));
  }

  private static InterfaceSelection interfaceSelection(ServerPlayer player, SmartInterfaceBlockEntity smart) {
    var entries = smart.entries();
    var selected = OWNERS.get(player.getUUID());
    if (selected != null) {
      var level = player.serverLevel();
      var owner = selected.identity().get();
      if (!selected.dimension().equals(level.dimension())
          || selected.expires() < level.getGameTime()
          || owner == null
          || owner.structureRemoved()
          || !level.hasChunkAt(selected.position())
          || level.getBlockEntity(selected.position()) != owner
          || !selected.machine().equals(owner.structureMachineId())
          || !level.mayInteract(player, selected.position())) {
        OWNERS.remove(player.getUUID());
        return new InterfaceSelection(null, "owner_required");
      }
      var matches = entries.stream()
          .filter(
              entry -> entry.binding().controller().equals(selected.position())
                  && entry.binding().machine().equals(selected.machine())
                  && entry.editable())
          .toList();
      return matches.size() == 1 ? new InterfaceSelection(matches.getFirst(), "") : new InterfaceSelection(
          null,
          "owner_required");
    }
    if (entries.size() != 1) {
      return new InterfaceSelection(null, entries.isEmpty() ? "unavailable" : "owner_required");
    }
    return entries.getFirst()
        .editable() ? new InterfaceSelection(entries.getFirst(), "") : new InterfaceSelection(null, "unavailable");
  }

  public static void clear(ServerPlayer player, InteractionHand hand) {
    ServerThreadGuard.require(player.serverLevel(), "Configuration card clear");
    if (hand != InteractionHand.MAIN_HAND
        || !editable(player)
        || !(player.getMainHandItem().getItem() instanceof ConfigurationCardItem)) {
      return;
    }
    player.getMainHandItem().remove(MachineDataComponents.CONFIGURATION_CARD.get());
    OWNERS.remove(player.getUUID());
    syncCard(player);
    message(player, "cleared");
  }

  public static Component typeName(Kind kind) {
    return Component.translatable("tooltip.mmcece.configuration_card.type." + kind.getSerializedName());
  }

  private static void syncCard(ServerPlayer player) {
    player.getInventory().setChanged();
    player.inventoryMenu.broadcastChanges();
  }

  private static void message(ServerPlayer player, String key, Object... arguments) {
    player.displayClientMessage(Component.translatable("message.mmcece.configuration_card." + key, arguments), true);
  }

  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    OWNERS.remove(event.getEntity().getUUID());
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    OWNERS.clear();
  }

  @SubscribeEvent
  public static void unloaded(LevelEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel level) {
      OWNERS.values().removeIf(owner -> owner.dimension().equals(level.dimension()));
    }
  }
}
