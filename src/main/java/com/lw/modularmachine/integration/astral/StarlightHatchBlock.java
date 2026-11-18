package com.lw.modularmachine.integration.astral;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.registry.MachineRegistries;
import hellfirepvp.astralsorcery.common.lib.BlocksAS;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class StarlightHatchBlock extends TickingRecipeHatchBlock {
  private final boolean input;

  public StarlightHatchBlock(Properties properties, boolean input) {
    super(properties, input ? StarlightHatch.Input::new : StarlightHatch.Output::new);
    this.input = input;
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    if (!input) {
      return;
    }
    var state = defaultBlockState();
    boolean basic = state.is(MachineRegistries.STARLIGHT_INPUT.get());
    boolean brilliant = state.is(MachineRegistries.STARLIGHT_INPUT_BRILLIANT.get());
    var altar = basic ? BlocksAS.ALTAR_RESONANCE.get() : BlocksAS.ALTAR_RADIANCE.get();
    String comparison = brilliant ? "tooltip.mmcece.starlight_input_hatch.four_times" : "tooltip.mmcece.starlight_input_hatch.same";
    tooltip.add(
        Component.translatable("tooltip.mmcece.starlight_input_hatch", StarlightHatch.inputCapacity(state) / 1_000)
            .withStyle(ChatFormatting.GRAY));
    tooltip.add(Component.translatable(comparison, altar.getName()).withStyle(ChatFormatting.GRAY));
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(position) instanceof StarlightHatch<?> hatch) {
      hatch.openMenu(player);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (!state.is(replacement.getBlock()) && level.getBlockEntity(position) instanceof StarlightHatch<?> hatch) {
      hatch.onTileEntityRemove(level, position);
    }
    super.onRemove(state, level, position, replacement, moving);
  }
}
