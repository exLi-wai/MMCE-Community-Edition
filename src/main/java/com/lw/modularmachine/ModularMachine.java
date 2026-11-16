package com.lw.modularmachine;

import com.lw.modularmachine.registry.MachineBootstrap;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ModularMachine.MODID)
public class ModularMachine {
  public static final String MODID = "mmcece";
  public static final Logger LOGGER = LogUtils.getLogger();

  public ModularMachine(IEventBus modEventBus, ModContainer modContainer) {
    MachineBootstrap.initialize(modEventBus, modContainer);
  }
}