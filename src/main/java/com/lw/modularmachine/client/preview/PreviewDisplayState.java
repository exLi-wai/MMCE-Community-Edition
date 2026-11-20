package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.Util;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
/**
 * One screen's pending/visible selections, scene publication, statistics and
 * animation clock.
 */
@ApiStatus.Internal
final class PreviewDisplayState {
  interface Host {
    int initialLength(String key, int minimum, int maximum);
    boolean failed();
    void cleared();
    void selectionPublished();
    void sceneChanged();
    void showLayer(Integer layer);
  }
  private final Host host;
  MachinePreviewDefinition definition;
  MachinePreviewDefinition pendingDefinition;
  MachinePreviewSelection pendingSelection;
  int color, pendingColor;
  MachinePreviewSelection selection;
  final Map<String, Boolean> includedGroups = new LinkedHashMap<>();
  final PreviewRenderer renderer = PreviewRenderer.create();
  PreviewSceneData scene;
  boolean hasAnimatedEntries;
  int step;
  long lastCycle;
  PreviewSceneStatistics statistics;
  Integer shownY;
  boolean cycle = true;

  PreviewDisplayState(Host host) {
    this.host = host;
  }

  /**
   * Live sources can finish the current display before capturing further changes.
   */
  boolean preparing() {
    return pendingSelection != null && pendingSelection.building()
        || selection != null && selection.building()
        || statistics != null && !statistics.ready();
  }

  /** Only completed, nonempty scenes may be exported by an editor subclass. */
  @Nullable
  Map<String, Integer> completedLengths(MachinePreviewDefinition expected) {
    if (expected == null
        || definition != expected
        || pendingSelection != null
        || selection == null
        || scene == null
        || scene.entries().isEmpty()
        || preparing()
        || host.failed()) {
      return null;
    }
    var lengths = new LinkedHashMap<String, Integer>();
    for (int index = 0; index < definition.segments().size(); index++) {
      lengths.put(definition.segments().get(index).id(), selection.length(index));
    }

    return Map.copyOf(lengths);
  }

  /**
   * A replacement is published after its complete display layout has been built.
   */
  void set(MachinePreviewDefinition next, int nextColor) {
    if (next == null) {
      definition = null;
      pendingDefinition = null;
      selection = null;
      pendingSelection = null;
      scene = null;
      statistics = null;
      shownY = null;
      hasAnimatedEntries = false;
      host.sceneChanged();
      renderer.close();
      host.cleared();
      return;
    }
    if (pendingDefinition == next && pendingColor == nextColor
        || pendingSelection == null && definition == next && color == nextColor) {
      return;
    }
    pendingDefinition = next;
    pendingColor = nextColor;
    int[] lengths = new int[next.segments().size()];
    for (int i = 0; i < lengths.length; i++) {
      var segment = next.segments().get(i);
      lengths[i] = host.initialLength(segment.id(), segment.minimum(), segment.maximum());
    }
    pendingSelection = new MachinePreviewSelection(next, nextColor, lengths);
  }

  void advance(int budget, long deadline) {
    if (pendingSelection != null) {
      budget -= pendingSelection.advance(budget, deadline);
      if (!pendingSelection.building() && (!pendingSelection.failed() || selection == null)) {
        for (int i = 0; i <= pendingDefinition.segments().size(); i++) {
          String key = i == 0 ? "" : pendingDefinition.segments().get(i - 1).id();
          if (!includedGroups.getOrDefault(key, true)) {
            pendingSelection.toggleIncluded(i);
          }
        }
        definition = pendingDefinition;
        color = pendingColor;
        selection = pendingSelection;
        pendingDefinition = null;
        pendingSelection = null;
        host.selectionPublished();
      }
    } else if (selection != null) {
      budget -= selection.advance(budget, deadline);
    }
    if (selection != null && selection.scene() != scene && selection.scene() != null) {
      scene = selection.scene();
      hasAnimatedEntries = scene.entries().stream().anyMatch(entry -> entry.states().size() > 1);
      renderer.setScene(scene);
      statistics = new PreviewSceneStatistics(scene);
      host.sceneChanged();
    }
    if (statistics != null && !statistics.ready()) {
      statistics.advance(budget, deadline);
    }
    if (shownY != null && statistics != null && statistics.ready() && !statistics.layers.containsKey(shownY)) {
      host.showLayer(null);
    }
    if (cycle && hasAnimatedEntries && Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
    }
  }
}
