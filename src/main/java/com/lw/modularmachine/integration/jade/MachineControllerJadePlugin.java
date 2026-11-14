package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.NetworkPortBlock;
import com.lw.modularmachine.block.PatternMirrorBlock;
import com.lw.modularmachine.block.PatternProviderBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public final class MachineControllerJadePlugin implements IWailaPlugin {
  @Override
  public void register(IWailaCommonRegistration registration) {
    registration.registerBlockDataProvider(MachineControllerTooltipProvider.INSTANCE, MachineControllerBlock.class);
    registration.registerBlockDataProvider(FactoryControllerTooltipProvider.INSTANCE, FactoryControllerBlock.class);
    registration.registerBlockDataProvider(NetworkDeviceTooltipProvider.INSTANCE, NetworkPortBlock.class);
    registration.registerBlockDataProvider(NetworkDeviceTooltipProvider.INSTANCE, PatternProviderBlock.class);
    registration.registerBlockDataProvider(NetworkDeviceTooltipProvider.INSTANCE, PatternMirrorBlock.class);
  }

  @Override
  public void registerClient(IWailaClientRegistration registration) {
    registration.registerBlockComponent(MachineControllerTooltipProvider.INSTANCE, MachineControllerBlock.class);
    registration.registerBlockComponent(FactoryControllerTooltipProvider.INSTANCE, FactoryControllerBlock.class);
    registration.registerBlockComponent(NetworkDeviceTooltipProvider.INSTANCE, NetworkPortBlock.class);
    registration.registerBlockComponent(NetworkDeviceTooltipProvider.INSTANCE, PatternProviderBlock.class);
    registration.registerBlockComponent(NetworkDeviceTooltipProvider.INSTANCE, PatternMirrorBlock.class);
  }
}
