package com.lw.modularmachine.client;

import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.menu.MachineResourceMenu;
import com.lw.modularmachine.network.MachinePayloads;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MachineResourceScreen extends AbstractContainerScreen<MachineResourceMenu> {
  public MachineResourceScreen(MachineResourceMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    MachineControllerStatusClient.attach(this);
    imageWidth = 176;
    imageHeight = 166;
    inventoryLabelY = 72;
  }

  @Override
  protected void init() {
    super.init();
    if (menu.port().canGroupInput()) {
      addRenderableWidget(InputGroupWidgets.entry(menu.containerId, leftPos + imageWidth, topPos + 4));
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    int x = leftPos;
    int y = topPos;
    var texture = ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/guibar.png");
    graphics.blit(texture, x, y, 0, 0, imageWidth, imageHeight, 256, 256);
    var status = menu.status();
    if (status != null && status.kind().resource() == PortKind.Resource.ENERGY) {
      long amount = status.amount();
      long capacity = status.capacity();
      drawEnergyFill(graphics, x, y, fillPixels(amount, capacity));
    } else if (status != null) {
      var fluid = status.fluid();
      int filled = fillPixels(status.amount(), status.capacity());
      if (!fluid.isEmpty() && filled > 0) {
        var extension = IClientFluidTypeExtensions.of(fluid.getFluidType());
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(extension.getStillTexture(fluid));
        int tint = extension.getTintColor(fluid);
        graphics.setColor((tint >> 16 & 255) / 255.0F, (tint >> 8 & 255) / 255.0F, (tint & 255) / 255.0F, 1.0F);
        graphics.blit(x + 15, y + 10 + 61 - filled, 0, 20, filled, sprite);
        graphics.setColor(1, 1, 1, 1);
      }
    }
    graphics.blit(texture, x + 15, y + 10, 176, 0, 20, 61, 256, 256);
  }

  private static void drawEnergyFill(GuiGraphics graphics, int x, int y, int filled) {
    if (filled > 0) {
      graphics.blit(
          ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/guibar.png"),
          x + 15,
          y + 10 + 61 - filled,
          196,
          61 - filled,
          20,
          filled,
          256,
          256);
    }
  }

  private static int fillPixels(long amount, long capacity) {
    return amount <= 0 || capacity <= 0 ? 0 : (int) Math.min(61, Math.max(0, Math.ceil(amount * 61.0 / capacity)));
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    int localX = (int) mouseX - leftPos;
    int localY = (int) mouseY - topPos;
    if (button == 0
        && menu.port().kind().resource() == PortKind.Resource.FLUID
        && localX >= 15
        && localX <= 35
        && localY >= 10
        && localY <= 71) {
      var player = Minecraft.getInstance().player;
      if (player != null) {
        InteractionHand hand = !player.getMainHandItem()
            .isEmpty() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (!player.getItemInHand(hand).isEmpty()) {
          PacketDistributor.sendToServer(
              new MachinePayloads.ResourcePortActionPayload(
                  MachinePayloads.PROTOCOL_VERSION,
                  menu.port().getBlockPos(),
                  hand));
          return true;
        }
      }
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
    int localX = mouseX - leftPos;
    int localY = mouseY - topPos;
    if (localX < 15 || localX > 35 || localY < 10 || localY > 71) {
      return;
    }
    var status = menu.status();
    if (status == null) {
      return;
    }
    if (status.kind().resource() == PortKind.Resource.ENERGY) {
      graphics.renderComponentTooltip(
          font,
          List.of(
              Component.translatable(
                  "message.mmcece.energy_port",
                  status.amount(),
                  status.capacity(),
                  status.transferLimit())),
          mouseX,
          mouseY);
    } else {
      var fluid = status.fluid();
      graphics.renderComponentTooltip(
          font,
          List.of(
              fluid.isEmpty() ? Component.translatable("message.mmcece.fluid_empty") : fluid.getHoverName(),
              Component.translatable("message.mmcece.fluid_port", fluid.getAmount(), status.capacity())),
          mouseX,
          mouseY);
    }
  }
}
