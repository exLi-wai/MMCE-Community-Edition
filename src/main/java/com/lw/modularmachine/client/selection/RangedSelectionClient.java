package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.machine.selection.RangedSelectionData.Snapshot;
import com.lw.modularmachine.network.RangedSelectionPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Client mirror of the server-owned editor and its world selection boxes. */
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class RangedSelectionClient {
  private static Snapshot snapshot;

  private RangedSelectionClient() {
  }

  public static Snapshot snapshot() {
    return snapshot;
  }

  public static void receive(Snapshot value) {
    var minecraft = Minecraft.getInstance();
    if (minecraft.level == null || !minecraft.level.dimension().location().equals(value.dimension())) {
      return;
    }
    if (snapshot != null && snapshot.session().equals(value.session()) && snapshot.revision() > value.revision()) {
      return;
    }
    snapshot = value;
    if (value.close()) {
      if (minecraft.screen instanceof RangedConstructToolScreen) {
        minecraft.setScreen(null);
      }
      return;
    }
    if (minecraft.screen instanceof RangedConstructToolScreen screen) {
      screen.accept(value);
    } else if (value.open()) {
      minecraft.setScreen(new RangedConstructToolScreen(value));
    }
  }

  public static void receivePreview(RangedSelectionPayloads.Preview value) {
    if (Minecraft.getInstance().screen instanceof RangedConstructToolScreen screen) {
      screen.acceptPreview(value);
    }
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    snapshot = null;
  }
}
