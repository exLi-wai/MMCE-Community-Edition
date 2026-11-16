package com.lw.modularmachine.client;

import com.lw.modularmachine.client.network.ClientPayloadReceivers;
import com.lw.modularmachine.client.preview.MachineDetailsScreen;
import com.lw.modularmachine.client.selection.RangedSelectionClient;
import com.lw.modularmachine.config.MachineClientConfig;
import com.lw.modularmachine.machine.blueprint.BlueprintPreviewActions;
import com.lw.modularmachine.machine.selection.RangedConstructToolActions;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client config and bindings installed once by the client-only mod entry point.
 */
@ApiStatus.Internal
public final class ClientBootstrap {
  private ClientBootstrap() {
  }

  public static void initialize(ModContainer container) {
    container.registerConfig(ModConfig.Type.CLIENT, MachineClientConfig.SPEC, "modularmachinery/mmcece-client.toml");
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    ClientPayloadReceivers.registerStatusReceivers();
    registerToolActions();
    ClientPayloadReceivers.registerCatalogReceivers();
  }

  private static void registerToolActions() {
    RangedConstructToolActions.setClientUse(RangedSelectionClient::use);
    BlueprintPreviewActions
        .setClientOpener(machine -> Minecraft.getInstance().setScreen(new MachineDetailsScreen(machine)));
  }
}
