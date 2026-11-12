package com.lw.modularmachine.client;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.network.NetworkPortPayloads;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Shared resource-specific interface configuration and legacy output-buffer
 * layout.
 */
public final class NetworkPortScreen extends AbstractContainerScreen<NetworkPortMenu> {
  private static final ResourceLocation INPUT_TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/interface.png");
  private static final ResourceLocation OUTPUT_TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/me_output.png");
  private final boolean input;
  private final Button[] amountButtons = new Button[9];
  private NetworkPortAmountScreen amountScreen;
  private int selected;

  public NetworkPortScreen(NetworkPortMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    input = menu.port().kind().isInput();
    imageWidth = 176;
    imageHeight = input ? 207 : 195;
    inventoryLabelY = input ? 112 : 101;
  }

  public static void receive(NetworkPortPayloads.Status status) {
    var player = Minecraft.getInstance().player;
    if (player != null && player.containerMenu instanceof NetworkPortMenu menu) {
      menu.acceptStatus(status);
    }
  }

  @Override
  protected void init() {
    super.init();
    if (input) {
      for (int index = 0; index < amountButtons.length; index++) {
        int slot = index;
        amountButtons[index] = addRenderableWidget(
            NetworkPortAmountWidgets.gear(leftPos + 8 + index * 18, topPos + 35, () -> openAmount(slot)));
      }
      if (menu.port().canGroupInput()) {
        addRenderableWidget(InputGroupWidgets.entry(menu.containerId, leftPos + imageWidth, topPos + 4));
      }
    }
    syncStatus();
    if (amountScreen != null) {
      amountScreen.init(minecraft, width, height);
    }
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    syncStatus();
    if (amountScreen != null) {
      amountScreen.tick();
    }
  }

  private void syncStatus() {
    if (!input) {
      return;
    }
    var status = menu.status();
    for (int slot = 0; slot < amountButtons.length; slot++) {
      amountButtons[slot].visible = status != null && status.snapshot().rows().get(slot).configured() != null;
      amountButtons[slot].active = status != null && status.editable();
    }
  }

  private void openAmount(int slot) {
    var status = menu.status();
    if (status != null && status.editable() && status.snapshot().rows().get(slot).configured() != null) {
      selected = slot;
      amountScreen = new NetworkPortAmountScreen(this, slot, () -> amountScreen = null);
      amountScreen.init(minecraft, width, height);
    }
  }

  private void request(NetworkPortPayloads.Operation operation, int value) {
    var status = menu.status();
    if (status != null && status.editable()) {
      menu.request(operation, selected, value);
    }
  }

  /**
   * Internal viewer bridge. Only the visible input configuration row accepts
   * ghosts.
   */
  public boolean canAcceptGhosts() {
    var client = Minecraft.getInstance();
    var status = menu.status();
    return input
        && amountScreen == null
        && client.screen == this
        && client.player != null
        && client.player.containerMenu == menu
        && status != null
        && status.editable();
  }

  public Rect2i ghostArea(int slot) {
    if (slot < 0 || slot >= 9) {
      throw new IndexOutOfBoundsException(slot);
    }
    return new Rect2i(leftPos + 8 + slot * 18, topPos + 54, 16, 16);
  }

  public void acceptGhost(int slot, NetworkPortResource resource, UUID session, long revision) {
    if (canAcceptGhosts() && resource.kind() == menu.port().kind().resource()) {
      menu.requestGhost(slot, resource, session, revision);
    }
  }

  @Override
  public void onClose() {
    if (amountScreen != null) {
      amountScreen.onClose();
    } else {
      super.onClose();
    }
  }

  @Override
  public boolean keyPressed(int key, int scanCode, int modifiers) {
    if (amountScreen != null) {
      return amountScreen.keyPressed(key, scanCode, modifiers);
    }

    return super.keyPressed(key, scanCode, modifiers);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (amountScreen != null) {
      return amountScreen.mouseClicked(mouseX, mouseY, button);
    }
    int x = (int) mouseX - leftPos;
    int y = (int) mouseY - topPos;
    int slot = slotAt(x, y, 54, 1);
    if (input && slot >= 0 && (button == 0 || button == 1)) {
      selected = slot;
      if (button == 1) {
        request(NetworkPortPayloads.Operation.CLEAR, 0);
      } else if (!menu.getCarried().isEmpty()) {
        var status = menu.status();
        int target = status == null ? 1 : Math.max(1, status.snapshot().rows().get(slot).target());
        request(NetworkPortPayloads.Operation.SELECT_CARRIED, target);
      }
      return true;
    }

    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean charTyped(char value, int modifiers) {
    return amountScreen == null ? super.charTyped(value, modifiers) : amountScreen.charTyped(value, modifiers);
  }

  @Override
  public boolean keyReleased(int key, int scanCode, int modifiers) {
    return amountScreen == null ? super.keyReleased(key, scanCode, modifiers) : amountScreen
        .keyReleased(key, scanCode, modifiers);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    return amountScreen == null ? super.mouseReleased(x, y, button) : amountScreen.mouseReleased(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    return amountScreen == null ? super.mouseDragged(x, y, button, dx, dy) : amountScreen
        .mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    return amountScreen == null ? super.mouseScrolled(x, y, scrollX, scrollY) : amountScreen
        .mouseScrolled(x, y, scrollX, scrollY);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(input ? INPUT_TEXTURE : OUTPUT_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    var status = menu.status();
    if (status == null) {
      return;
    }
    var rows = status.snapshot().rows();
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      int x = leftPos + 8 + index % 9 * 18;
      if (input) {
        drawResource(graphics, row.configured(), row.target(), x, topPos + 54);
        drawResource(graphics, row.buffered(), row.stored(), x, topPos + 72);
      } else {
        drawResource(graphics, row.buffered(), row.stored(), x, topPos + 24 + index / 9 * 18);
      }
    }
    if (input) {
      int x = leftPos + 7 + selected * 18;
      graphics.renderOutline(x, topPos + 53, 18, 18, 0xFF4A70A0);
    }
  }

  void drawResource(GuiGraphics graphics, NetworkPortResource resource, long count, int x, int y) {
    drawResource(graphics, resource, count, x, y, true);
  }

  void drawResource(GuiGraphics graphics, NetworkPortResource resource, long count, int x, int y, boolean showQuantity) {
    if (resource == null || count <= 0) {
      return;
    }
    switch (resource) {
      case NetworkPortResource.Item item -> graphics.renderItem(item.stack(), x, y);
      case NetworkPortResource.Fluid fluid -> {
        var stack = fluid.stack();
        var extension = IClientFluidTypeExtensions.of(stack.getFluidType());
        drawSprite(graphics, extension.getStillTexture(stack), extension.getTintColor(stack), x, y);
      }
      case NetworkPortResource.Chemical chemical -> {
        var appearance = ChemicalDisplay.describe(new ChemicalStackData(chemical.id(), 1));
        drawSprite(graphics, appearance.texture(), appearance.tint(), x, y);
      }
    }
    if (!showQuantity) {
      return;
    }
    String quantity = compact(count);
    graphics.pose().pushPose();
    graphics.pose().translate(x + 16, y + 12, 200);
    graphics.pose().scale(0.5F, 0.5F, 1);
    graphics.drawString(font, quantity, -font.width(quantity), 0, 0xFFFFFF, true);
    graphics.pose().popPose();
  }

  private static void drawSprite(GuiGraphics graphics, ResourceLocation texture, int tint, int x, int y) {
    TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
    graphics.setColor((tint >> 16 & 255) / 255.0F, (tint >> 8 & 255) / 255.0F, (tint & 255) / 255.0F, 1);
    graphics.blit(x, y, 0, 16, 16, sprite);
    graphics.setColor(1, 1, 1, 1);
  }

  private static String compact(long value) {
    if (value < 1000) {
      return Long.toString(value);
    }
    String[] units = {"k", "M", "G", "T", "P", "E"};
    double scaled = value;
    int index = -1;
    do {
      scaled /= 1000;
      index++;
    } while (scaled >= 1000 && index + 1 < units.length);

    return String.format(Locale.ROOT, "%.1f%s", scaled, units[index]);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 8, 0x404040, false);
    graphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0x404040, false);
    if (input) {
      var status = menu.status();
      if (status != null) {
        String key = !status.snapshot().supported() ? "unsupported" : status.snapshot().active() ? "online" : "offline";
        graphics.drawString(font, Component.translatable("menu.mmcece.network." + key), 8, 97, 0x404040, false);
      }
    }
  }

  private static int slotAt(int x, int y, int firstY, int rows) {
    int column = (x - 8) / 18;
    int row = (y - firstY) / 18;
    if (x < 8 || y < firstY || column >= 9 || row >= rows || (x - 8) % 18 >= 16 || (y - firstY) % 18 >= 16) {
      return -1;
    }

    return row * 9 + column;
  }

  static Component resourceName(NetworkPortResource value) {
    return switch (value) {
      case NetworkPortResource.Item item -> item.stack().getHoverName();
      case NetworkPortResource.Fluid fluid -> fluid.stack().getHoverName();
      case NetworkPortResource.Chemical chemical ->
        ChemicalDisplay.describe(new ChemicalStackData(chemical.id(), 1)).name();
    };
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    if (amountScreen != null) {
      amountScreen.render(graphics, mouseX, mouseY, partialTick);
      return;
    }
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
    var status = menu.status();
    if (status == null) {
      return;
    }
    int x = mouseX - leftPos;
    int y = mouseY - topPos;
    int slot = input ? slotAt(x, y, 54, 1) : -1;
    boolean configured = slot >= 0;
    if (!configured) {
      slot = slotAt(x, y, input ? 72 : 24, input ? 1 : 4);
    }
    if (slot >= 0) {
      var row = status.snapshot().rows().get(slot);
      var resource = configured ? row.configured() : row.buffered();
      long count = configured ? row.target() : row.stored();
      var tooltip = new ArrayList<Component>();
      if (resource != null) {
        tooltip.add(resourceName(resource));
        tooltip.add(
            Component.translatable(
                configured ? "menu.mmcece.network.target" : "menu.mmcece.network.stored",
                Long.toString(count)));
      }
      if (configured) {
        tooltip.add(
            Component.translatable(
                "menu.mmcece.network.configure." + status.snapshot().resource().name().toLowerCase(Locale.ROOT)));
        tooltip.add(Component.translatable("menu.mmcece.network.clear"));
      }
      if (!tooltip.isEmpty()) {
        graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
      }
    }
  }
}
