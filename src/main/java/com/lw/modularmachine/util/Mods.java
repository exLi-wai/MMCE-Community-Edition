package com.lw.modularmachine.util;

import net.neoforged.fml.ModList;

public enum Mods {
  AE2("ae2"),
  MEKANISM("mekanism"),
  GTM("gtceu"),
  JEC("jecharacters");

  public final String modid;

  Mods(String modid) {
    this.modid = modid;
  }

  public boolean isLoaded() {
    var mods = ModList.get();

    return mods != null && mods.isLoaded(modid);
  }
}
