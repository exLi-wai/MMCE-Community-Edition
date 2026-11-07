package com.lw.modularmachine.api.client.preview;

import com.lw.modularmachine.client.preview.CachedPreviewRenderer;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Client-only structure preview service. Calls require the render thread.
 * Instances own their scene meshes and retain only read-only block states;
 * buttons, item lists and mouse bindings belong to the calling screen.
 */
public interface PreviewRenderer extends AutoCloseable {
  int ALL_LAYERS = Integer.MIN_VALUE;

  static PreviewRenderer create() {
    return new CachedPreviewRenderer();
  }

  /** Replaces the immutable scene and releases previous GPU resources. */
  void setScene(PreviewSceneData scene);

  /**
   * Renders in the caller's GUI pose. A specific layer uses a top view;
   * ALL_LAYERS uses the camera's rotation. Resources are built under the shared
   * mesh budget, so a new or invalidated scene may appear over several frames.
   */
  void render(GuiGraphics graphics, PreviewViewport viewport, PreviewCamera camera, int layer, int step);

  /**
   * Returns the first-input-order block index hit in the last rendered frame, or
   * -1. Pass mouse coordinates in the same pre-pose GUI coordinate space as the
   * viewport used by the last render. The renderer applies that GUI pose and the
   * viewport clip before picking.
   */
  int pick(double mouseX, double mouseY);

  /** Drops meshes and the last pick frame, retaining scene and caller camera. */
  void invalidate();

  /**
   * Releases native meshes and the last pick frame. Idempotent; rendering again
   * rebuilds from the retained scene, for viewers that reuse widgets on return.
   * Screen shutdown and resource reload also release managed preview meshes.
   */
  @Override
  void close();
}
