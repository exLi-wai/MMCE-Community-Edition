package com.lw.modularmachine.integration.astral;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import hellfirepvp.astralsorcery.common.linking.LinkContainer;
import hellfirepvp.astralsorcery.common.starlight.api.TransmissionSourceNode;
import hellfirepvp.astralsorcery.common.starlight.api.provider.TransmissionSourceNodeProvider;
import hellfirepvp.astralsorcery.common.starlight.transmission.StarlightTransmissionLevelHelper;
import hellfirepvp.astralsorcery.common.starlight.transmission.StarlightTransmissionPacket;
import hellfirepvp.astralsorcery.common.tile.network.SimpleTransmissionNode;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class StarlightOutputNode extends SimpleTransmissionNode implements TransmissionSourceNode {
  static final MapCodec<StarlightOutputNode> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance
          .group(
              BlockPos.CODEC.fieldOf("pos").forGetter(StarlightOutputNode::getNodePos),
              LinkContainer.CODEC.fieldOf("links").forGetter(node -> node.linkContainer))
          .apply(instance, StarlightOutputNode::new));

  StarlightOutputNode(BlockPos position, LinkContainer links) {
    super(position, links, 1F);
  }

  @Override
  public Optional<StarlightTransmissionPacket> produceStarlight(Level level) {
    if (!(level instanceof ServerLevel server)
        || !server.hasChunkAt(pos)
        || !(server.getBlockEntity(pos) instanceof StarlightHatch.Output hatch)
        || linkContainer.getLinkedToCount() == 0) {
      return Optional.empty();
    }
    return hatch.emit();
  }

  @Override
  public void updateLinkStateChange(ServerLevel level, BlockPos other, boolean linked) {
    super.updateLinkStateChange(level, other, linked);
    StarlightTransmissionLevelHelper.getInstance().getHandler(level)
        .ifPresent(handler -> handler.notifyNodeChange(this));
  }

  @Override
  public TransmissionSourceNodeProvider<?> getProvider() {
    return AstralIntegration.OUTPUT.get();
  }
}
