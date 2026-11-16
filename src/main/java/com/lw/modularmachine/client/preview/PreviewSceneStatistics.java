package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.jetbrains.annotations.ApiStatus;
@ApiStatus.Internal
/**
 * Statistics belong to the complete scene, independently of material-list
 * inclusion.
 */
final class PreviewSceneStatistics {
  final PreviewSceneData scene;
  final NavigableMap<Integer, Integer> layers = new TreeMap<>();
  int cursor, blocks;
  int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
  int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

  PreviewSceneStatistics(PreviewSceneData scene) {
    this.scene = scene;
  }

  boolean ready() {
    return cursor == scene.entries().size();
  }

  void advance(int budget, long deadline) {
    for (int work = 0; work < budget && !ready() && System.nanoTime() < deadline; work++) {
      var entry = scene.entries().get(cursor++);
      var pos = entry.position();
      minX = Math.min(minX, pos.getX());
      minY = Math.min(minY, pos.getY());
      minZ = Math.min(minZ, pos.getZ());
      maxX = Math.max(maxX, pos.getX());
      maxY = Math.max(maxY, pos.getY());
      maxZ = Math.max(maxZ, pos.getZ());
      boolean block = entry.states().stream().anyMatch(state -> !state.isAir());
      layers.merge(pos.getY(), block ? 1 : 0, Integer::sum);
      if (block) {
        blocks++;
      }
    }
  }

  long width() {
    return scene.entries().isEmpty() ? 0 : (long) maxX - minX + 1;
  }

  long height() {
    return scene.entries().isEmpty() ? 0 : (long) maxY - minY + 1;
  }

  long depth() {
    return scene.entries().isEmpty() ? 0 : (long) maxZ - minZ + 1;
  }
}
