package com.lw.modularmachine.integration.thaumaturge;

import com.leclowndu93150.thaumaturge.api.aspect.AspectComponents;
import com.leclowndu93150.thaumaturge.api.items.GogglesAccess;
import com.leclowndu93150.thaumaturge.content.research.pool.AspectPools;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin("thaumaturge")
public final class EssentiaJadePlugin implements IWailaPlugin, IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
  private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("mmcece", "essentia_contents");
  private static final String CONTENTS = UID.toString();

  @Override
  public void register(IWailaCommonRegistration registration) {
    registration.registerBlockDataProvider(this, EssentiaHatchBlock.class);
  }

  @Override
  public void registerClient(IWailaClientRegistration registration) {
    registration.registerBlockComponent(this, EssentiaHatchBlock.class);
  }

  @Override
  public ResourceLocation getUid() {
    return UID;
  }

  @Override
  public boolean shouldRequestData(BlockAccessor accessor) {
    return GogglesAccess.wearsRevealingGear(accessor.getPlayer());
  }

  @Override
  public void appendServerData(CompoundTag data, BlockAccessor accessor) {
    var player = accessor.getPlayer();
    if (!GogglesAccess.wearsRevealingGear(player) || !(accessor.getBlockEntity() instanceof EssentiaHatch hatch)) {
      return;
    }
    var rows = new ListTag();
    boolean available = hatch.visitStoredAspects((aspect, amount) -> {
      var row = new CompoundTag();
      if (AspectPools.isDiscovered(player, aspect)) {
        row.putString("aspect", aspect.unwrapKey().orElseThrow().location().toString());
      }
      row.putLong("amount", amount);
      rows.add(row);
    });
    if (available) {
      data.put(CONTENTS, rows);
    }
  }

  @Override
  public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
    var data = accessor.getServerData();
    if (!GogglesAccess.wearsRevealingGear(accessor.getPlayer()) || !data.contains(CONTENTS, Tag.TAG_LIST)) {
      return;
    }
    var rows = data.getList(CONTENTS, Tag.TAG_COMPOUND);
    if (rows.isEmpty()) {
      tooltip.add(Component.translatable("jade.thaumaturge.essentia.empty"));
      return;
    }
    int shown = accessor.showDetails() ? rows.size() : Math.min(6, rows.size());
    for (int i = 0; i < shown; i++) {
      var row = rows.getCompound(i);
      Component name = Component.translatable("tooltip.thaumaturge.aspect.unknown");
      if (row.contains("aspect", Tag.TAG_STRING)) {
        var id = ResourceLocation.tryParse(row.getString("aspect"));
        var aspect = id == null ? null : EssentiaHatch.resolveAspect(accessor.getLevel().registryAccess(), id);
        if (aspect != null) {
          name = AspectComponents.trueName(aspect).withColor(aspect.value().color());
        }
      }
      tooltip.add(Component.translatable("jade.thaumaturge.aspect_amount", name, row.getLong("amount")));
    }
    if (shown < rows.size()) {
      tooltip.add(Component.translatable("jade.mmcece.essentia.more", rows.size() - shown));
    }
  }
}
