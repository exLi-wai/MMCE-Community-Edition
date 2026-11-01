package com.lw.modularmachine.client;

import com.lw.modularmachine.machine.factory.FactoryUpgradeEntry;
import com.lw.modularmachine.menu.UpgradeBusMenu;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

public final class UpgradeBusScreen extends AbstractContainerScreen<UpgradeBusMenu> {
  private static final int FONT_COLOR = 0xFFFFFF;
  private static final int DESCRIPTION_X = 92;
  private static final int DESCRIPTION_Y = 23;
  private static final int DESCRIPTION_WIDTH = 89;
  private static final int DESCRIPTION_LINE_HEIGHT = 10;
  private static final int MAX_DESCRIPTION_LINES = 15;
  private static final int SCROLLBAR_X = 156;
  private static final int SCROLLBAR_Y = 17;
  private static final int SCROLLBAR_HEIGHT = 106;
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guiupgradebus.png");
  private final LegacyGuiScrollbar scrollbar = new LegacyGuiScrollbar();
  private UpgradeBusPayloads.Status cachedStatus;
  private Language cachedLanguage;
  private List<FormattedCharSequence> cachedDescription = List.of();

  public UpgradeBusScreen(UpgradeBusMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 213;
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    for (int slot = 0; slot < menu.upgradeSlots(); slot++) {
      int x = 8 + slot % 3 * 18;
      int y = 17 + slot / 3 * 18;
      graphics.blit(TEXTURE, leftPos + x - 1, topPos + y - 1, 7, 130, 18, 18, 256, 256);
    }
    scrollbar.position(leftPos + SCROLLBAR_X, topPos + SCROLLBAR_Y).height(SCROLLBAR_HEIGHT)
        .range(0, Math.max(0, descriptionLines().size() - MAX_DESCRIPTION_LINES), 1).draw(graphics);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, Component.translatable("menu.mmcece.upgrade_bus.title"), 7, 5, FONT_COLOR, true);
    var lines = descriptionLines();
    int maxScroll = Math.max(0, lines.size() - MAX_DESCRIPTION_LINES);
    int scroll = Math.min(scrollbar.current(), maxScroll);
    graphics.pose().pushPose();
    graphics.pose().scale(.72F, .72F, 1F);
    int x = (int) (DESCRIPTION_X / .72F);
    int y = (int) (DESCRIPTION_Y / .72F);
    for (int index = scroll; index < Math.min(lines.size(), scroll + MAX_DESCRIPTION_LINES); index++) {
      graphics.drawString(font, lines.get(index), x, y, FONT_COLOR, true);
      y += DESCRIPTION_LINE_HEIGHT;
    }
    graphics.pose().popPose();
  }

  private List<FormattedCharSequence> descriptionLines() {
    var status = menu.status();
    if (status == null) {
      return List.of();
    }
    var language = Language.getInstance();
    if (status == cachedStatus && language == cachedLanguage) {
      return cachedDescription;
    }
    var result = new ArrayList<FormattedCharSequence>();
    int textWidth = (int) (DESCRIPTION_WIDTH / .72F);
    if (!status.bindings().isEmpty()) {
      result.addAll(
          font.split(
              Component.translatable("menu.mmcece.upgrade_bus.bound_count", status.bindings().size()),
              textWidth));
      for (var binding : status.bindings()) {
        var position = binding.controller();
        result.addAll(
            font.split(
                Component.translatable(
                    "menu.mmcece.upgrade_bus.bound_machine",
                    binding.machine().toString(),
                    position.getX(),
                    position.getY(),
                    position.getZ()),
                textWidth));
        if (!binding.active() && binding.valid()) {
          result.addAll(font.split(Component.translatable("menu.mmcece.upgrade_bus.inactive"), textWidth));
        }
        appendDescription(
            result,
            binding.valid(),
            binding.machine(),
            binding.entries(),
            binding.incompatible(),
            binding.conflictCount(),
            binding.conflicts(),
            textWidth);
      }
    } else {
      appendDescription(
          result,
          status.valid(),
          status.machine(),
          status.entries(),
          status.incompatible(),
          status.conflictCount(),
          status.conflicts(),
          textWidth);
    }
    cachedStatus = status;
    cachedLanguage = language;
    cachedDescription = List.copyOf(result);

    return cachedDescription;
  }

  private void appendDescription(List<FormattedCharSequence> lines, boolean valid, ResourceLocation machine, List<FactoryUpgradeEntry> entries, List<ResourceLocation> incompatible, int conflictCount, List<ResourceLocation> conflicts, int width) {
    if (conflictCount > 0) {
      lines.addAll(font.split(Component.translatable("menu.mmcece.upgrade_bus.conflicts", conflictCount), width));
      for (var id : conflicts) {
        lines.addAll(font.split(Component.literal(id.toString()), width));
      }
      return;
    }
    if (!valid) {
      lines.addAll(font.split(Component.translatable("menu.mmcece.upgrade_bus.invalid"), width));
      return;
    }
    if (!incompatible.isEmpty()) {
      var reason = machine == null ? Component.translatable("menu.mmcece.upgrade_bus.unbound_filters") : Component
          .translatable("menu.mmcece.upgrade_bus.incompatible", machine.toString());
      lines.addAll(font.split(reason, width));
      for (var id : incompatible) {
        lines.addAll(font.split(Component.literal(id.toString()), width));
      }
    }
    for (var entry : entries) {
      appendEntry(lines, entry, width);
    }
  }

  private void appendEntry(List<FormattedCharSequence> lines, FactoryUpgradeEntry entry, int width) {
    lines.addAll(font.split(Component.literal(entry.id().toString()), width));
    lines.addAll(
        font.split(
            Component.translatable(
                "menu.mmcece.upgrade_bus.entry_effect",
                entry.parallelAdd(),
                entry.speedNumerator(),
                entry.speedDenominator(),
                entry.energyNumerator(),
                entry.energyDenominator(),
                entry.fluidNumerator(),
                entry.fluidDenominator()),
            width));
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int maxScroll = Math.max(0, descriptionLines().size() - MAX_DESCRIPTION_LINES);
    if (maxScroll == 0 || scrollY == 0) {
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    scrollbar.wheel(scrollY > 0 ? 1 : -1);

    return true;
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
