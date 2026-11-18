package com.lw.modularmachine.integration.botania;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SparkManaHatchBlock extends TickingRecipeHatchBlock {
  public SparkManaHatchBlock(Properties properties) {
    super(properties, SparkManaHatchBlockEntity::new);
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
    long stored = data == null ? 0 : data.copyTag().getLong(SparkManaHatchBlockEntity.MANA);
    int capacity = SparkManaHatchBlockEntity.CAPACITY;
    long mana = Math.clamp(stored, 0, capacity);
    tooltip
        .add(Component.translatable("tooltip.mmcece.spark_mana_hatch", mana, capacity).withStyle(ChatFormatting.GRAY));
  }
}
