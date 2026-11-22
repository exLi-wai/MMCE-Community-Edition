package com.lw.modularmachine.client;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.menu.MachineResourceMenu;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.util.minecraft.energy.EnergyDisplay;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MachineResourceScreen extends AbstractContainerScreen<MachineResourceMenu> {
  private static final ScreenRectangle GAUGE = new ScreenRectangle(15, 10, 20, 61);

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
    if (status != null && status.kind().resource() == ResourceType.ENERGY) {
      long amount = status.amount();
      long capacity = status.capacity();
      drawEnergyFill(graphics, x, y, fillPixels(amount, capacity));
    } else if (status != null && status.kind().resource() == ResourceType.CHEMICAL) {
      int filled = fillPixels(status.amount(), status.capacity());
      if (!status.chemical().isEmpty() && filled > 0) {
        var appearance = ChemicalDisplay.describe(status.chemical());
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(appearance.texture());
        int tint = appearance.tint();
        graphics.setColor((tint >> 16 & 255) / 255.0F, (tint >> 8 & 255) / 255.0F, (tint & 255) / 255.0F, 1.0F);
        graphics.blit(x + GAUGE.left(), y + GAUGE.bottom() - filled, 0, GAUGE.width(), filled, sprite);
        graphics.setColor(1, 1, 1, 1);
      }
    } else if (status != null) {
      var fluid = status.fluid();
      int filled = fillPixels(status.amount(), status.capacity());
      if (!fluid.isEmpty() && filled > 0) {
        var extension = IClientFluidTypeExtensions.of(fluid.getFluidType());
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(extension.getStillTexture(fluid));
        int tint = extension.getTintColor(fluid);
        graphics.setColor((tint >> 16 & 255) / 255.0F, (tint >> 8 & 255) / 255.0F, (tint & 255) / 255.0F, 1.0F);
        graphics.blit(x + GAUGE.left(), y + GAUGE.bottom() - filled, 0, GAUGE.width(), filled, sprite);
        graphics.setColor(1, 1, 1, 1);
      }
    }
    graphics.blit(texture, x + GAUGE.left(), y + GAUGE.top(), 176, 0, GAUGE.width(), GAUGE.height(), 256, 256);
  }

  private static void drawEnergyFill(GuiGraphics graphics, int x, int y, int filled) {
    if (filled > 0) {
      graphics.blit(
          ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/guibar.png"),
          x + GAUGE.left(),
          y + GAUGE.bottom() - filled,
          196,
          GAUGE.height() - filled,
          GAUGE.width(),
          filled,
          256,
          256);
    }
  }

  private static int fillPixels(long amount, long capacity) {
    return amount <= 0
        || capacity <= 0 ? 0 : (int) Math.min(GAUGE.height(), Math.ceil((double) amount * GAUGE.height() / capacity));
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0
        && (menu.port().kind().resource() == ResourceType.FLUID
            || menu.port().kind().resource() == ResourceType.CHEMICAL)
        && GAUGE.containsPoint((int) Math.floor(mouseX - leftPos), (int) Math.floor(mouseY - topPos))) {
      var player = Minecraft.getInstance().player;
      if (player != null) {
        InteractionHand hand = !player.getMainHandItem()
            .isEmpty() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (!player.getItemInHand(hand).isEmpty()) {
          PacketDistributor.sendToServer(
              new MachinePayloads.ResourcePortActionPayload(
                  MachinePayloads.RESOURCE_PROTOCOL_VERSION,
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
    if (!GAUGE.containsPoint(mouseX - leftPos, mouseY - topPos)) {
      return;
    }
    var status = menu.status();
    if (status == null) {
      return;
    }
    if (status.kind().resource() == ResourceType.ENERGY) {
      graphics.renderComponentTooltip(
          font,
          List.of(
              Component.translatable(
                  "message.mmcece.energy_port_unit",
                  EnergyDisplay.format(status.amount()),
                  EnergyDisplay.format(status.capacity()),
                  EnergyDisplay.format(status.transferLimit()),
                  EnergyDisplay.unitComponent())),
          mouseX,
          mouseY);
    } else if (status.kind().resource() == ResourceType.CHEMICAL) {
      graphics.renderComponentTooltip(
          font,
          List.of(
              ChemicalDisplay.describe(status.chemical()).name(),
              Component.translatable("message.mmcece.chemical_port", status.amount(), status.capacity())),
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
