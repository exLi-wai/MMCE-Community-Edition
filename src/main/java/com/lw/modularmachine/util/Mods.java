package com.lw.modularmachine.util;

import net.neoforged.fml.ModList;

public enum Mods {
  AE2("ae2"),
  APPMEK("appmek"),
  MEKANISM("mekanism"),
  EMBERS("embers"),
  BOTANIA("botania"),
  NATURES_AURA("naturesaura"),
  NEO_VITAE("neovitae"),
  THAUMATURGE("thaumaturge"),
  APPLIED_THAUMATURGE("appliedthaumaturge"),
  ASTRAL_SORCERY("astralsorcery"),
  PNEUMATIC_CRAFT("pneumaticcraft"),
  GTM("gtceu"),
  ATHENA("athena"),
  GECKOLIB("geckolib"),
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
