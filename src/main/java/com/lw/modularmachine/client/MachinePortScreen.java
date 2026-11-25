package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.MachinePortMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class MachinePortScreen extends AbstractContainerScreen<MachinePortMenu> {
  private int pressedMouseButtons;

  public MachinePortScreen(MachinePortMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 166;
    inventoryLabelY = 72;
  }

  @Override
  protected void init() {
    super.init();
    pressedMouseButtons = 0;
    if (menu.port().canGroupInput()) {
      addRenderableWidget(InputGroupWidgets.entry(menu.containerId, leftPos + imageWidth, topPos + 4));
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    int x = leftPos;
    int y = topPos;
    graphics.blit(backgroundTexture(), x, y, 0, 0, imageWidth, imageHeight, 256, 256);
    if (!menu.legacyLayout()) {
      renderGridBackground(graphics);
    }
  }

  private ResourceLocation backgroundTexture() {
    String name = "inventory_ludicrous.png";
    if (menu.legacyLayout()) {
      name = switch (menu.tierName()) {
        case "tiny", "small", "normal", "reinforced", "big", "huge", "ludicrous" ->
          "inventory_" + menu.tierName() + ".png";
        default -> "inventory_normal.png";
      };
    }

    return ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/" + name);
  }

  private void renderGridBackground(GuiGraphics graphics) {
    int first = menu.scrollRow() * MachinePortMenu.GRID_COLUMNS;
    for (int cell = 0; cell < MachinePortMenu.VISIBLE_SLOTS; cell++) {
      if (first + cell >= menu.portSlots()) {
        int x = leftPos + 16 + cell % MachinePortMenu.GRID_COLUMNS * 18;
        int y = topPos + 7 + cell / MachinePortMenu.GRID_COLUMNS * 18;
        graphics.fill(x, y, x + 18, y + 18, 0xFFC6C6C6);
      }
    }
    int maximum = menu.maximumScrollRow();
    if (maximum > 0) {
      int x = leftPos + 164;
      int y = topPos + 8;
      int height = 70;
      int thumbHeight = Math.max(8, height * MachinePortMenu.VISIBLE_ROWS / menu.rows());
      int thumbY = y + (height - thumbHeight) * menu.scrollRow() / maximum;
      graphics.fill(x, y, x + 4, y + height, 0xFF8B8B8B);
      graphics.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xFFEEEEEE);
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (menu.maximumScrollRow() == 0 || scrollY == 0 || !isHovering(16, 7, 154, 72, mouseX, mouseY)) {
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    if (pressedMouseButtons != 0 || isQuickCrafting || minecraft.gameMode == null) {
      return true;
    }
    int next = Math.max(0, Math.min(menu.maximumScrollRow(), menu.scrollRow() + (scrollY > 0 ? -1 : 1)));
    if (menu.scrollTo(next)) {
      hoveredSlot = null;
      quickCraftSlots.clear();
      minecraft.gameMode.handleInventoryButtonClick(menu.containerId, menu.scrollButton(next));
    }

    return true;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button >= 0 && button < Integer.SIZE) {
      pressedMouseButtons |= 1 << button;
    }

    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (button >= 0 && button < Integer.SIZE) {
      pressedMouseButtons &= ~(1 << button);
    }

    return super.mouseReleased(mouseX, mouseY, button);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }
}
