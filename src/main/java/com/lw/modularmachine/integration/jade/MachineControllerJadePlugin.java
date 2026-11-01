package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
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
  }

  @Override
  public void registerClient(IWailaClientRegistration registration) {
    registration.registerBlockComponent(MachineControllerTooltipProvider.INSTANCE, MachineControllerBlock.class);
    registration.registerBlockComponent(FactoryControllerTooltipProvider.INSTANCE, FactoryControllerBlock.class);
  }
}
