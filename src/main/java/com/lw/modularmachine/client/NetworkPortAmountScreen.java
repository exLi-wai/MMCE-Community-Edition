package com.lw.modularmachine.client;

import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.network.NetworkPortPayloads;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * A separate quantity page hosted by the existing container screen. Switching
 * pages never closes its server menu or releases the player's carried stack.
 */
final class NetworkPortAmountScreen extends Screen {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("ae2", "textures/guis/craft_amt.png");
  private static final ResourceLocation TEXT_FIELD = ResourceLocation
      .fromNamespaceAndPath("ae2", "textures/guis/text_field.png");
  private static final int[] STEPS = {1, 10, 100, 1000};
  private static final int[] MODIFIED_STEPS = {1, 16, 32, 64};
  private final NetworkPortScreen parent;
  private final NetworkPortMenu menu;
  private final int slot;
  private final UUID session;
  private final long revision;
  private final NetworkPortResource resource;
  private final int initialAmount;
  private final Runnable returnToPort;
  private final Button[] increases = new Button[4];
  private final Button[] decreases = new Button[4];
  private EditBox amount;
  private Button save;
  private Button back;
  private int left;
  private int top;

  NetworkPortAmountScreen(NetworkPortScreen parent, int slot, Runnable returnToPort) {
    super(Component.translatable("menu.mmcece.network.amount"));
    this.parent = parent;
    menu = parent.getMenu();
    this.slot = slot;
    this.returnToPort = returnToPort;
    var status = menu.status();
    session = status.session();
    revision = status.snapshot().configRevision();
    var row = status.snapshot().rows().get(slot);
    resource = row.configured();
    initialAmount = row.target();
  }

  @Override
  protected void init() {
    left = (width - 176) / 2;
    top = (height - 107) / 2;
    String value = amount == null ? Integer.toString(initialAmount) : amount.getValue();
    amount = addRenderableWidget(new EditBox(font, left + 50, top + 57, 62 - font.width("_"), 8, title));
    amount.setBordered(false);
    amount.setMaxLength(10);
    amount.setFilter(text -> text.matches("[0-9]*"));
    amount.setValue(value);
    setInitialFocus(amount);
    amount.setHighlightPos(0);
    int[] positions = {20, 48, 82, 120};
    int[] widths = {22, 28, 32, 38};
    for (int index = 0; index < 4; index++) {
      int step = index;
      increases[index] = addRenderableWidget(
          NetworkPortAmountWidgets
              .button(left + positions[index], top + 30, widths[index], Component.empty(), () -> adjust(step, true)));
      decreases[index] = addRenderableWidget(
          NetworkPortAmountWidgets
              .button(left + positions[index], top + 72, widths[index], Component.empty(), () -> adjust(step, false)));
    }
    save = addRenderableWidget(
        NetworkPortAmountWidgets
            .button(left + 120, top + 51, 38, Component.translatable("menu.mmcece.network.save"), this::submit));
    back = addRenderableWidget(NetworkPortAmountWidgets.back(left + 152, top - 5, this::onClose));
    updateButtons();
  }

  private boolean current() {
    var status = menu.status();
    return minecraft.player != null
        && minecraft.player.containerMenu == menu
        && status != null
        && status.editable()
        && session.equals(status.session())
        && revision == status.snapshot().configRevision()
        && status.snapshot().rows().get(slot).configured() != null;
  }

  @Override
  public void tick() {
    if (!current()) {
      onClose();
    }
  }

  private int parsedAmount() {
    try {
      return Math.max(0, Integer.parseInt(amount.getValue()));
    } catch (NumberFormatException invalid) {
      return 0;
    }
  }

  private static int step(int index) {
    return hasShiftDown() || hasControlDown() ? MODIFIED_STEPS[index] : STEPS[index];
  }

  private void adjust(int index, boolean increase) {
    long value = Math.max(1, parsedAmount());
    value += increase ? step(index) : -step(index);
    amount.setValue(Long.toString(Math.clamp(value, 1L, Integer.MAX_VALUE)));
  }

  private void updateButtons() {
    boolean editable = current();
    int value = parsedAmount();
    amount.active = editable;
    amount.setEditable(editable);
    amount.setTextColor(value > 0 ? 0xF2F2F2 : 0xFF1900);
    save.active = editable && value > 0;
    for (int index = 0; index < 4; index++) {
      increases[index].setMessage(Component.literal("+" + step(index)));
      decreases[index].setMessage(Component.literal("-" + step(index)));
      increases[index].active = editable && value < Integer.MAX_VALUE;
      decreases[index].active = editable && value > 1;
    }
  }

  private void submit() {
    if (current() && parsedAmount() > 0) {
      menu.request(NetworkPortPayloads.Operation.SET_AMOUNT, slot, parsedAmount());
      onClose();
    }
  }

  @Override
  public void onClose() {
    returnToPort.run();
  }

  @Override
  public boolean keyPressed(int key, int scanCode, int modifiers) {
    if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
      submit();
      return true;
    }
    return super.keyPressed(key, scanCode, modifiers);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // Screen.render calls this before its widgets. Match container screens and
    // never run the generic blur pass after drawing the quantity panel.
    renderTransparentBackground(graphics);
    graphics.blit(TEXTURE, left, top, 0, 0, 176, 107, 256, 256);
    // AE draws this separately from craft_amt.png around a borderless EditBox.
    int fieldV = !amount.active ? 12 : amount.isFocused() ? 24 : 0;
    graphics.blit(TEXT_FIELD, left + 48, top + 55, 0, fieldV, 1, 12, 128, 128);
    graphics.blit(TEXT_FIELD, left + 49, top + 55, 1, fieldV, 64, 12, 128, 128);
    graphics.blit(TEXT_FIELD, left + 113, top + 55, 127, fieldV, 1, 12, 128, 128);
    graphics.drawString(font, title, left + 8, top + 6, 0x404040, false);
    parent.drawResource(graphics, resource, 1, left + 23, top + 53, false);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    updateButtons();
    super.render(graphics, mouseX, mouseY, partialTick);
    if (amount.isMouseOver(mouseX, mouseY)) {
      graphics.renderTooltip(font, Component.translatable("menu.mmcece.network.range"), mouseX, mouseY);
    } else if (back.isMouseOver(mouseX, mouseY)) {
      graphics.renderTooltip(font, back.getMessage(), mouseX, mouseY);
    } else if (mouseX >= left + 23 && mouseX < left + 39 && mouseY >= top + 53 && mouseY < top + 69) {
      graphics.renderTooltip(font, NetworkPortScreen.resourceName(resource), mouseX, mouseY);
    }
  }
}
