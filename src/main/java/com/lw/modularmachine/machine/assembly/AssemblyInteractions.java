package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.item.ConfigurationCardItem;
import com.lw.modularmachine.machine.structure.StructureService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Configurable legacy assembly binding, using the normal right-click packet
 * once.
 */
public final class AssemblyInteractions {
  private AssemblyInteractions() {
  }

  public static boolean matches(ItemStack stack) {
    var id = ResourceLocation.tryParse(MachineConfig.ASSEMBLY_ITEM.get());
    var item = id == null ? Items.STICK : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.STICK);
    return !stack.isEmpty() && stack.is(item);
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
    if (event.isCanceled()
        || event.getItemStack().getItem() instanceof RangedConstructToolItem
        || event.getItemStack().getItem() instanceof ConfigurationCardItem
        || event.getHand() != InteractionHand.MAIN_HAND
        || event.getEntity().isShiftKeyDown()
        || !matches(event.getItemStack())) {
      return;
    }
    var level = event.getLevel();
    if (!level.hasChunkAt(event.getPos())
        || !(level.getBlockEntity(event.getPos()) instanceof StructureService.StructureSubject controller)) {
      return;
    }
    if (event.getUseBlock().isFalse() || event.getUseItem().isFalse()) {
      event.setCanceled(true);
      event.setCancellationResult(InteractionResult.FAIL);
      return;
    }
    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    if (event.getEntity() instanceof ServerPlayer player && matches(player.getMainHandItem())) {
      AssemblyService.request(player, controller, true, InteractionHand.MAIN_HAND);
    }
  }
}
