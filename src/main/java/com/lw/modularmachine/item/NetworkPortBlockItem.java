package com.lw.modularmachine.item;

import com.lw.modularmachine.block.NetworkPortBlock;
import com.lw.modularmachine.api.component.ResourceType;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Portable buffers retain quantities; a placed item always creates a fresh
 * network node.
 */
public final class NetworkPortBlockItem extends BlockItem {
  private static final Map<ResourceType, Predicate<CompoundTag>> PORTABLE_BUFFERS = Map.of(
      ResourceType.ITEM,
      physical -> physical.contains("items", Tag.TAG_COMPOUND)
          && physical.getCompound("items").contains("Size", Tag.TAG_INT)
          && physical.getCompound("items").contains("BulkItems", Tag.TAG_LIST),
      ResourceType.FLUID,
      physical -> physical.contains("fluid", Tag.TAG_COMPOUND),
      ResourceType.CHEMICAL,
      physical -> physical.contains("chemical", Tag.TAG_COMPOUND));

  public NetworkPortBlockItem(Block block, Properties properties) {
    super(block, properties);
  }

  @Override
  protected BlockState getPlacementState(BlockPlaceContext context) {
    var data = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
    if (data != null) {
      var root = data.copyTag();
      // Check the carried envelope before vanilla recursively fills missing fields
      // from a new empty block entity. Keep malformed portable data on the item.
      if ((root.contains("network_port") || root.contains("mmcece")) && !completePortableState(root)) {
        return null;
      }
    }
    return super.getPlacementState(context);
  }

  private boolean completePortableState(CompoundTag root) {
    if (!root.contains("network_port", Tag.TAG_COMPOUND) || !root.contains("mmcece", Tag.TAG_COMPOUND)) {
      return false;
    }
    var kind = ((NetworkPortBlock) getBlock()).kind();
    var network = root.getCompound("network_port");
    var physical = root.getCompound("mmcece");
    if (!network.contains("schema", Tag.TAG_INT)
        || network.getInt("schema") != 1
        || !network.getString("kind").equals(kind.name())
        || !network.contains("config", Tag.TAG_LIST)
        || !network.contains("extra_buffers", Tag.TAG_LIST)
        || !physical.contains("schema", Tag.TAG_INT)
        || physical.getInt("schema") != 1) {
      return false;
    }
    var buffers = PORTABLE_BUFFERS.get(kind.resource());

    return buffers != null && buffers.test(physical);
  }

  @Override
  protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
    var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
    if (data == null) {
      return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }
    var root = data.copyTag();
    root.put("network_connection", new CompoundTag());
    root.remove("network_client");
    var sanitized = stack.copy();
    sanitized.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(root));
    return super.updateCustomBlockEntityTag(pos, level, player, sanitized, state);
  }
}
