package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.GroupInputMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Shared side-tab entry without changing legacy inventory or tank geometry. */
final class InputGroupWidgets {
  private InputGroupWidgets() {
  }

  static Button entry(int containerId, int left, int top) {
    return Button.builder(Component.translatable("menu.mmcece.group.open"), button -> {
      var minecraft = Minecraft.getInstance();
      if (minecraft.gameMode != null) {
        minecraft.gameMode.handleInventoryButtonClick(containerId, GroupInputMenu.OPEN_BUTTON);
      }
    }).bounds(left, top, 40, 20).build();
  }
}
