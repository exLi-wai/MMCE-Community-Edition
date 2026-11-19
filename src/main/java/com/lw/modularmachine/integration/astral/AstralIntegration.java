package com.lw.modularmachine.integration.astral;

import com.lw.modularmachine.ModularMachine;
import com.mojang.serialization.MapCodec;
import hellfirepvp.astralsorcery.common.lib.RegistriesAS;
import hellfirepvp.astralsorcery.common.starlight.api.provider.TransmissionNodeProvider;
import hellfirepvp.astralsorcery.common.starlight.api.provider.TransmissionSourceNodeProvider;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;
import hellfirepvp.astralsorcery.common.linking.LinkContainer;
import net.neoforged.neoforge.registries.DeferredHolder;

@ApiStatus.Internal
public final class AstralIntegration {
  private static final DeferredRegister<TransmissionNodeProvider<?>> NODES = DeferredRegister
      .create(RegistriesAS.KEY_TRANSMISSION_NODES, ModularMachine.MODID);

  static final DeferredHolder<TransmissionNodeProvider<?>, OutputProvider> OUTPUT = NODES
      .register("starlight_output", OutputProvider::new);

  private AstralIntegration() {
  }

  public static void initialize(IEventBus bus) {
    NODES.register(bus);
  }

  static final class OutputProvider implements TransmissionSourceNodeProvider<StarlightOutputNode> {
    @Override
    public StarlightOutputNode provideNewNode(BlockPos position) {
      return new StarlightOutputNode(position, new LinkContainer());
    }

    @Override
    public MapCodec<StarlightOutputNode> nodeCodec() {
      return StarlightOutputNode.CODEC;
    }
  }
}
