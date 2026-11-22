package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.client.ChemicalDisplay;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.IDisplayHelper;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

final class PatternCacheDisplay {
  private static final Vec2 ICON_SIZE = new Vec2(10, 10);
  private static final NumberFormat ITEM_COUNT = NumberFormat.getIntegerInstance(Locale.ROOT);
  private static final String[] VOLUME_UNITS = {"mB", "B", "kB", "MB", "GB", "TB", "PB"};

  private PatternCacheDisplay() {
  }

  static void append(ITooltip tooltip, BlockAccessor accessor) {
    var data = accessor.getServerData();
    if (!data.contains(PatternCacheTooltip.CACHE, Tag.TAG_COMPOUND)) {
      return;
    }
    var preview = data.getCompound(PatternCacheTooltip.CACHE);
    for (var kind : PatternCacheTooltip.KINDS) {
      for (var entry : preview.getList(kind.name(), Tag.TAG_COMPOUND)) {
        appendRow(tooltip, accessor, (CompoundTag) entry);
      }
    }
    if (preview.getLong("more") > 0) {
      tooltip.add(
          Component.translatable("jade.mmcece.pattern_cache.more", preview.getLong("more"))
              .withStyle(ChatFormatting.GRAY));
    }
  }

  private static void appendRow(ITooltip tooltip, BlockAccessor accessor, CompoundTag row) {
    var helper = IElementHelper.get();
    var resource = NetworkPortResource.load(accessor.getLevel().registryAccess(), row.getCompound("resource"));
    IElement icon;
    Component name;
    switch (resource) {
      case NetworkPortResource.Item item -> {
        var stack = item.stack();
        icon = helper.smallItem(stack);
        name = stack.getHoverName();
      }
      case NetworkPortResource.Fluid fluid -> {
        var stack = fluid.stack();
        var sample = JadeFluidObject.of(stack.getFluid(), JadeFluidObject.bucketVolume(), stack.getComponentsPatch());
        icon = helper.fluid(sample).size(ICON_SIZE).translate(new Vec2(0, -1));
        name = stack.getHoverName();
      }
      case NetworkPortResource.Chemical chemical -> {
        var appearance = ChemicalDisplay.describe(new ChemicalStackData(chemical.id(), 1));
        icon = new ChemicalIcon(appearance).translate(new Vec2(0, -1));
        name = appearance.name();
      }
      default -> {
        return;
      }
    }
    if (row.contains("name")) {
      name = ComponentSerialization.CODEC.parse(accessor.nbtOps(), row.get("name")).result().orElse(name);
    }
    boolean isItem = resource instanceof NetworkPortResource.Item;
    long amount = row.getLong("amount");
    var quantity = Component.literal(isItem ? ITEM_COUNT.format(amount) : formatVolume(amount))
        .withStyle(ChatFormatting.DARK_PURPLE);
    var label = Component.empty().append(quantity);
    label.append(Component.literal(isItem ? "× " : " ").withStyle(ChatFormatting.WHITE));
    label.append(name.copy().withStyle(isItem ? ChatFormatting.GOLD : ChatFormatting.DARK_AQUA));
    tooltip.add(List.of(icon, helper.spacer(3, 0), helper.text(label)));
  }

  private static String formatVolume(long milliBuckets) {
    var amount = BigDecimal.valueOf(milliBuckets);
    int unit = 0;
    while (unit < VOLUME_UNITS.length - 1 && amount.compareTo(BigDecimal.valueOf(1000)) >= 0) {
      amount = amount.movePointLeft(3);
      unit++;
    }
    amount = amount.setScale(2, RoundingMode.HALF_UP);
    if (unit < VOLUME_UNITS.length - 1 && amount.compareTo(BigDecimal.valueOf(1000)) >= 0) {
      amount = amount.movePointLeft(3);
      unit++;
    }
    return amount.stripTrailingZeros().toPlainString() + VOLUME_UNITS[unit];
  }

  private static final class ChemicalIcon extends Element {
    private final ChemicalDisplay.Appearance appearance;

    private ChemicalIcon(ChemicalDisplay.Appearance appearance) {
      this.appearance = appearance;
    }

    @Override
    public Vec2 getSize() {
      return ICON_SIZE;
    }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float width, float height) {
      var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(appearance.texture());
      int tint = appearance.tint();
      graphics.blit(
          Math.round(x),
          Math.round(y),
          0,
          10,
          10,
          sprite,
          ((tint >> 16) & 255) / 255F,
          ((tint >> 8) & 255) / 255F,
          (tint & 255) / 255F,
          IDisplayHelper.get().opacity());
    }
  }
}
