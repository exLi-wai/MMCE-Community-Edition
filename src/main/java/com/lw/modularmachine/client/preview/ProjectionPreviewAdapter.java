package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.List;

/**
 * Network-to-render input conversion; all input values are already detached.
 */
final class ProjectionPreviewAdapter {
  private ProjectionPreviewAdapter() {
  }

  static PreviewSceneData sceneData(int machineColor, List<ProjectionPayloads.Entry> entries) {
    return new PreviewSceneData(
        machineColor,
        entries.stream().map(entry -> new PreviewSceneData.Entry(entry.position(), entry.states(), entry.properties()))
            .toList());
  }
}
