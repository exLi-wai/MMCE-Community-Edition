package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.network.MachinePayloads;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class FactoryControllerScreen extends AbstractContainerScreen<FactoryControllerMenu> {
  private static final ResourceLocation FACTORY_TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guifactory.png");
  private static final ResourceLocation ELEMENT_TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guifactoryelements.png");
  private static final int MAX_VISIBLE_THREADS = 6;
  private static final int THREAD_X = 8;
  private static final int THREAD_Y = 8;
  private static final int THREAD_WIDTH = 86;
  private static final int THREAD_HEIGHT = 32;
  private static final int ELEMENT_TEXTURE_SIZE = 256;
  private static final int THREAD_STEP = 33;
  private static final int FONT_COLOR = 0x222222;
  private static final int INFO_COLOR = 0xFFFFFF;
  private static final int SCROLLBAR_X = 94;
  private static final int SCROLLBAR_Y = 8;
  private static final int SCROLLBAR_HEIGHT = 197;
  private final LegacyGuiScrollbar scrollbar = new LegacyGuiScrollbar();

  public FactoryControllerScreen(FactoryControllerMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 280;
    imageHeight = 213;
  }

  @Override
  protected void init() {
    super.init();
    MachineControllerStatusClient.attach(this);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(FACTORY_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    List<MachinePayloads.FactoryStatusThread> threads = threads();
    int maxScroll = Math.max(0, threads.size() - MAX_VISIBLE_THREADS);
    scrollbar.position(leftPos + SCROLLBAR_X, topPos + SCROLLBAR_Y).height(SCROLLBAR_HEIGHT).range(0, maxScroll, 1)
        .draw(graphics);
    int threadScroll = Math.min(scrollbar.current(), maxScroll);
    for (int row = 0; row < Math.min(MAX_VISIBLE_THREADS, threads.size() - threadScroll); row++) {
      MachinePayloads.FactoryStatusThread thread = threads.get(row + threadScroll);
      int x = leftPos + THREAD_X;
      int y = topPos + THREAD_Y + row * THREAD_STEP;
      graphics
          .blit(ELEMENT_TEXTURE, x, y, 0, 0, THREAD_WIDTH, THREAD_HEIGHT, ELEMENT_TEXTURE_SIZE, ELEMENT_TEXTURE_SIZE);
      int progressWidth = progressWidth(thread);
      if (progressWidth > 0) {
        if ("RUNNING".equals(thread.state())) {
          graphics.setColor(.6F, 1F, .75F, 1F);
        } else {
          graphics.setColor(1F, .6F, .6F, 1F);
        }
        graphics.blit(
            ELEMENT_TEXTURE,
            x,
            y,
            0,
            0,
            progressWidth,
            THREAD_HEIGHT,
            ELEMENT_TEXTURE_SIZE,
            ELEMENT_TEXTURE_SIZE);
        graphics.setColor(1F, 1F, 1F, 1F);
      }
    }
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    List<MachinePayloads.FactoryStatusThread> threads = threads();
    int threadScroll = Math.min(scrollbar.current(), Math.max(0, threads.size() - MAX_VISIBLE_THREADS));
    for (int row = 0; row < Math.min(MAX_VISIBLE_THREADS, threads.size() - threadScroll); row++) {
      drawThreadStatus(graphics, threads.get(row + threadScroll), row);
    }
    graphics.pose().pushPose();
    graphics.pose().scale(.72F, .72F, 1F);
    int infoX = (int) (113 / .72F);
    int infoY = 12;
    var status = menu.status();
    boolean formed = status != null && "CONFIRMED".equals(status.structureState());
    Component blueprint = ControllerStatusLabels.blueprint(menu);
    if (blueprint != null || !formed) {
      infoY = drawWrapped(
          graphics,
          Component.translatable(
              "menu.mmcece.controller.blueprint",
              blueprint == null ? ControllerStatusLabels.none() : blueprint),
          infoX,
          infoY) + 5;
    }
    if (status == null) {
      drawWrapped(graphics, Component.translatable("menu.mmcece.controller.status_waiting"), infoX, infoY);
      graphics.pose().popPose();
      return;
    }
    Component structure = ControllerStatusLabels.structure(status.structureState(), status.machineId());
    infoY = drawWrapped(
        graphics,
        Component.translatable(
            "menu.mmcece.controller.structure",
            structure == null ? ControllerStatusLabels.none() : structure),
        infoX,
        infoY) + 5;
    infoY = drawWrapped(
        graphics,
        Component.translatable("menu.mmcece.controller.status", ControllerStatusLabels.factoryState(status)),
        infoX,
        infoY) + 5;
    infoY = drawWrapped(
        graphics,
        Component.translatable("menu.mmcece.factory.threads", threads.size()),
        infoX,
        infoY) + 5;
    if ("INVALID".equals(status.structureState())) {
      infoY = drawWrapped(
          graphics,
          Component.translatable(
              "menu.mmcece.factory.structure_failure",
              Component.translatable("jade.mmcece.failure." + status.structureFailure().toLowerCase(Locale.ROOT))),
          infoX,
          infoY) + 5;
    }
    ControllerStatusLabels
        .drawPerformance(graphics, font, infoX, infoY, status.averageTickMicros(), status.averageSearchMicros());
    graphics.pose().popPose();
  }

  private int drawWrapped(GuiGraphics graphics, Component text, int x, int y) {
    for (var line : font.split(text, 187)) {
      graphics.drawString(font, line, x, y, INFO_COLOR, true);
      y += 10;
    }

    return y;
  }

  private void drawThreadStatus(GuiGraphics graphics, MachinePayloads.FactoryStatusThread thread, int row) {
    graphics.pose().pushPose();
    graphics.pose().scale(.72F, .72F, 1F);
    int x = (int) ((THREAD_X + 2) / .72F);
    int y = (int) ((THREAD_Y + row * THREAD_STEP + 2) / .72F);
    graphics.drawString(font, Component.literal(thread.threadId()), x, y, FONT_COLOR, false);
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.factory.thread_state." + thread.state().toLowerCase(Locale.ROOT)),
        x,
        y + 12,
        FONT_COLOR,
        false);
    if (thread.lockedRecipe() != null && thread.duration() == 0) {
      graphics.drawString(font, Component.literal(thread.lockedRecipe().toString()), x, y + 22, FONT_COLOR, false);
    } else if (thread.duration() == 0 && thread.parallelLimit() > 1) {
      graphics.drawString(font, Component.literal("x" + thread.parallelLimit()), x, y + 22, FONT_COLOR, false);
    }
    if (thread.duration() > 0) {
      graphics.drawString(
          font,
          Component.translatable(
              "menu.mmcece.controller.progress",
              ControllerStatusLabels.percent(thread.progress(), thread.duration()) + "%"),
          x,
          y + 22,
          FONT_COLOR,
          false);
    } else if (!"NONE".equals(thread.reason())) {
      graphics.drawString(
          font,
          Component.translatable("menu.mmcece.factory.reason." + thread.reason().toLowerCase(Locale.ROOT)),
          x,
          y + 22,
          FONT_COLOR,
          false);
    }
    graphics.pose().popPose();
  }

  private static int progressWidth(MachinePayloads.FactoryStatusThread thread) {
    if (thread.duration() <= 0 || thread.progress() <= 0) {
      return 0;
    }

    return (int) Math.min(THREAD_WIDTH, (long) THREAD_WIDTH * thread.progress() / thread.duration());
  }

  private List<MachinePayloads.FactoryStatusThread> threads() {
    var status = menu.status();
    return status == null ? List.of() : status.threads();
  }

  public FactoryControllerMenu getMenu() {
    return menu;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int maxScroll = Math.max(0, threads().size() - MAX_VISIBLE_THREADS);
    if (maxScroll > 0 && scrollY != 0) {
      scrollbar.wheel(scrollY > 0 ? 1 : -1);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    scrollbar.click(mouseX, mouseY);
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    if (button == 0) {
      scrollbar.click(mouseX, mouseY);
    }

    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
  }
}
