package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import mekanism.api.MekanismAPI;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Runtime Chemical platform adapter, installed by the Mekanism module. */
@ApiStatus.Internal
final class MekanismChemicalAdapter implements ChemicalPlatform.Adapter {
  @Override
  public boolean contains(ResourceLocation chemical) {
    return MekanismAPI.CHEMICAL_REGISTRY.getHolder(chemical)
        .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY)).isPresent();
  }

  @Override
  public boolean hasContainer(ItemStack stack) {
    return !stack.isEmpty() && Capabilities.CHEMICAL.getCapability(stack) != null;
  }

  @Override
  public ChemicalStackData sampleContainer(ItemStack stack) {
    var handler = Capabilities.CHEMICAL.getCapability(stack);
    if (handler == null || handler.getChemicalTanks() > 64) {
      return ChemicalStackData.EMPTY;
    }
    for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
      var chemical = handler.getChemicalInTank(tank);
      if (!chemical.isEmpty()) {
        return MekanismChemicalStacks.data(chemical).withAmount(1);
      }
    }
    return ChemicalStackData.EMPTY;
  }

  @Override
  public boolean interact(Player player, InteractionHand hand, MachinePortBlockEntity port) {
    return MekanismChemicalContainers.interact(player, hand, port);
  }
}
