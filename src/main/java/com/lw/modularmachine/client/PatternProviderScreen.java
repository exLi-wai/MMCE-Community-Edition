package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.PatternProviderMenu;
import com.lw.modularmachine.network.PatternProviderPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Legacy provider layout with a separate, paged view of each physical pattern
 * cache.
 */
public final class PatternProviderScreen extends AbstractContainerScreen<PatternProviderMenu> {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/mepatternprovider.png");
  private boolean draggingScroll;

  public PatternProviderScreen(PatternProviderMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 256;
    imageHeight = 196;
    inventoryLabelX = 7;
    inventoryLabelY = 101;
  }

  public static void receive(PatternProviderPayloads.Status status) {
    var player = Minecraft.getInstance().player;
    if (player != null && player.containerMenu instanceof PatternProviderMenu menu) {
      menu.acceptStatus(status);
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    for (int index = 0; index < 36; index++) {
      var slot = menu.getSlot(index);
      if (!slot.hasItem()) {
        NetworkPortAmountWidgets.patternSlotBackground(graphics, leftPos + slot.x, topPos + slot.y);
      }
    }
    graphics.blit(
        TEXTURE,
        leftPos + 215,
        topPos + 7,
        inside(mouseX, mouseY, 215, 7, 16, 16) ? 194 : 176,
        214,
        16,
        16,
        256,
        256);
    graphics.blit(
        TEXTURE,
        leftPos + 240,
        topPos + 157,
        inside(mouseX, mouseY, 240, 157, 9, 11) ? 241 : 230,
        214,
        9,
        11,
        256,
        256);
    var status = menu.status();
    if (status == null) {
      return;
    }
    var snapshot = status.snapshot();
    var entries = snapshot.entries();
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.get(index);
      int x = leftPos + 180 + index % 3 * 18;
      int y = topPos + 27 + index / 3 * 18;
      graphics.blit(TEXTURE, x, y, 220, 232, 18, 18, 256, 256);
      NetworkPortScreen.drawResource(graphics, font, entry.resource(), entry.amount(), x + 1, y + 1, true);
    }
    int maxRow = Math.max(0, snapshot.totalRows() - 7);
    int thumbY = maxRow == 0 ? 0 : (int) ((long) snapshot.firstRow() * 106 / maxRow);
    int thumbU = maxRow == 0 ? 209 : draggingScroll ? 198 : inside(mouseX, mouseY, 240, 28, 9, 124) ? 187 : 176;
    graphics.blit(TEXTURE, leftPos + 240, topPos + 28 + thumbY, thumbU, 232, 9, 18, 256, 256);
    NetworkPortScreen
        .drawResource(graphics, font, snapshot.fluid(), snapshot.fluidAmount(), leftPos + 232, topPos + 172, true);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 7, 11, 0x404040, false);
    graphics.drawString(font, Component.translatable("menu.mmcece.pattern.cached"), 180, 11, 0x404040, false);
    graphics.drawString(font, playerInventoryTitle, 7, 101, 0x404040, false);
    graphics.drawString(font, Component.translatable("menu.mmcece.pattern.independent"), 180, 158, 0x404040, false);
    int entry = cacheAt(mouseX, mouseY);
    if (menu.status() != null && entry >= 0 && entry < menu.status().snapshot().entries().size()) {
      renderSlotHighlight(graphics, 181 + entry % 3 * 18, 28 + entry / 3 * 18, 0);
    } else if (inside(mouseX, mouseY, 232, 172, 16, 16)) {
      renderSlotHighlight(graphics, 232, 172, 0);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    var tooltip = new ArrayList<Component>();
    if (inside(mouseX, mouseY, 215, 7, 16, 16)) {
      tooltip.add(Component.translatable("menu.mmcece.pattern.return"));
      tooltip.add(Component.translatable("menu.mmcece.pattern.return.description"));
    } else if (inside(mouseX, mouseY, 240, 157, 9, 11)) {
      tooltip.add(Component.translatable("menu.mmcece.pattern.independent.description"));
    } else if (menu.status() != null) {
      var snapshot = menu.status().snapshot();
      int entry = cacheAt(mouseX, mouseY);
      if (entry >= 0 && entry < snapshot.entries().size()) {
        var value = snapshot.entries().get(entry);
        tooltip.add(NetworkPortScreen.resourceName(value.resource()));
        tooltip.add(Component.translatable("menu.mmcece.pattern.amount", value.amount()));
        tooltip.add(Component.translatable("menu.mmcece.pattern.slot", snapshot.selectedSlot() + 1));
        if (value.retired()) {
          tooltip.add(Component.translatable("menu.mmcece.pattern.retired"));
        }
      } else if (inside(mouseX, mouseY, 232, 172, 16, 16)) {
        if (snapshot.fluid() != null) {
          tooltip.add(NetworkPortScreen.resourceName(snapshot.fluid()));
        }
        tooltip
            .add(Component.translatable("menu.mmcece.pattern.fluid", snapshot.fluidAmount(), snapshot.fluidCapacity()));
        tooltip.add(Component.translatable("menu.mmcece.pattern.fluid.description"));
      } else if (patternAt(mouseX, mouseY) >= 0
          && menu.getCarried().isEmpty()
          && !menu.getSlot(patternAt(mouseX, mouseY)).hasItem()) {
        tooltip.add(Component.translatable("menu.mmcece.pattern.select"));
      }
    }
    if (!tooltip.isEmpty()) {
      graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    } else {
      renderTooltip(graphics, mouseX, mouseY);
    }
  }

  @Override
  protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
    var tooltip = new ArrayList<>(super.getTooltipFromContainerItem(stack));
    if (hoveredSlot != null && hoveredSlot.index < 36) {
      tooltip.add(Component.empty());
      tooltip.add(Component.translatable("menu.mmcece.pattern.select").withStyle(ChatFormatting.GRAY));
    }
    return tooltip;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    var status = menu.status();
    if (status != null) {
      int slot = status.snapshot().selectedSlot();
      if (button == 0 && inside(mouseX, mouseY, 215, 7, 16, 16)) {
        menu.request(PatternProviderPayloads.Operation.RETURN_ALL, slot, 0);
        return true;
      }
      if ((button == 0 || button == 1) && inside(mouseX, mouseY, 232, 172, 16, 16)) {
        menu.request(PatternProviderPayloads.Operation.FLUID_CONTAINER, slot, 0);
        return true;
      }
      if (button == 0 && inside(mouseX, mouseY, 240, 28, 9, 124)) {
        draggingScroll = true;
        scrollTo(mouseY);
        return true;
      }
      int selected = patternAt(mouseX, mouseY);
      if (button == 0 && selected >= 0 && menu.getCarried().isEmpty() && !hasShiftDown()) {
        menu.request(PatternProviderPayloads.Operation.SELECT_SLOT, selected, 0);
        return true;
      }
    }

    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (menu.status() != null && inside(x, y, 180, 27, 69, 126)) {
      var snapshot = menu.status().snapshot();
      int row = Math
          .clamp((long) snapshot.firstRow() - (int) Math.signum(scrollY), 0, Math.max(0, snapshot.totalRows() - 7));
      menu.request(PatternProviderPayloads.Operation.SCROLL, snapshot.selectedSlot(), row);
      return true;
    }

    return super.mouseScrolled(x, y, scrollX, scrollY);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (draggingScroll && button == 0) {
      scrollTo(y);
      return true;
    }

    return super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    if (draggingScroll && button == 0) {
      draggingScroll = false;
      return true;
    }

    return super.mouseReleased(x, y, button);
  }

  private void scrollTo(double y) {
    if (menu.status() != null) {
      var snapshot = menu.status().snapshot();
      int maxRow = Math.max(0, snapshot.totalRows() - 7);
      int row = (int) Math.round(Math.clamp((y - topPos - 37) / 106, 0, 1) * maxRow);
      menu.request(PatternProviderPayloads.Operation.SCROLL, snapshot.selectedSlot(), row);
    }
  }

  private int patternAt(double x, double y) {
    if (!inside(x, y, 8, 28, 162, 72)) {
      return -1;
    }
    int localX = (int) x - leftPos - 8;
    int localY = (int) y - topPos - 28;

    return localX % 18 < 16 && localY % 18 < 16 ? localY / 18 * 9 + localX / 18 : -1;
  }

  private int cacheAt(double x, double y) {
    if (!inside(x, y, 181, 28, 54, 126)) {
      return -1;
    }
    int localX = (int) x - leftPos - 181;
    int localY = (int) y - topPos - 28;

    return localX % 18 < 16 && localY % 18 < 16 ? localY / 18 * 3 + localX / 18 : -1;
  }

  private boolean inside(double x, double y, int left, int top, int width, int height) {
    return x >= leftPos + left && x < leftPos + left + width && y >= topPos + top && y < topPos + top + height;
  }
}
