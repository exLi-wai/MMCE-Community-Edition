package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Snapshot;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.network.RangedSelectionPayloads.Preview;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client-thread preview assembly retained for the new editor. No
 * receiver is installed; callers retain their last complete scene themselves.
 */
@ApiStatus.Internal
public final class RangedSelectionPreviewAssembler {
  private static final int MAX_ENTRIES = 4_194_304;
  private final List<ProjectionPayloads.Entry> incoming = new ArrayList<>();
  private UUID session;
  private ResourceLocation dimension;
  private long revision = -1;
  private long lastRequest = -1;
  private Preview header;

  /**
   * Returns a scene only after every contiguous chunk has passed validation.
   * Missing worlds, stale packets and failed/incomplete streams return empty.
   */
  public Optional<PreviewSceneData> accept(Snapshot state, ResourceLocation currentDimension, Preview value) {
    var packet = value.preview();
    if (!state.dimension().equals(currentDimension)
        || !state.session().equals(value.session())
        || state.revision() != value.revision()
        || !packet.dimension().equals(currentDimension)
        || packet.epoch() != value.revision()) {
      return Optional.empty();
    }
    if (!state.session().equals(session) || state.revision() != revision || !state.dimension().equals(dimension)) {
      reset();
      session = state.session();
      dimension = state.dimension();
      revision = state.revision();
    }
    if (value.request() < lastRequest) {
      return Optional.empty();
    }
    if (packet.start() == -1) {
      lastRequest = value.request();
      incoming.clear();
      header = null;
      return Optional.empty();
    }
    if (packet.start() == 0) {
      if (header == null && value.request() <= lastRequest) {
        return Optional.empty();
      }
      lastRequest = value.request();
      header = value;
      incoming.clear();
    }
    if (header == null
        || value.request() != header.request()
        || packet.total() != header.preview().total()
        || packet.epoch() != header.preview().epoch()
        || !packet.machine().equals(header.preview().machine())
        || value.color() != header.color()
        || !packet.origin().equals(header.preview().origin())
        || incoming.size() != packet.start()
        || packet.total() > MAX_ENTRIES) {
      return Optional.empty();
    }
    incoming.addAll(packet.entries());
    if (incoming.size() != packet.total()) {
      return Optional.empty();
    }
    var complete = incoming.stream()
        .map(entry -> new PreviewSceneData.Entry(entry.position(), entry.states(), entry.properties())).toList();
    var scene = new PreviewSceneData(value.color(), complete);
    incoming.clear();
    header = null;

    return Optional.of(scene);
  }

  /** Releases partial data when the owning editor closes or disconnects. */
  public void reset() {
    incoming.clear();
    header = null;
    session = null;
    dimension = null;
    revision = -1;
    lastRequest = -1;
  }
}
