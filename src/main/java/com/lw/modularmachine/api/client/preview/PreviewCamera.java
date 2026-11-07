package com.lw.modularmachine.api.client.preview;

/** Per-view camera state, with no mouse binding or dependency on a GUI host. */
public final class PreviewCamera {
  private final float pitchLimit;
  private float yaw = -135, pitch = 25, zoom = 1, panX, panY;

  public PreviewCamera() {
    this(90);
  }

  public PreviewCamera(float pitchLimit) {
    if (!Float.isFinite(pitchLimit) || pitchLimit < 0 || pitchLimit > 90) {
      throw new IllegalArgumentException("Preview pitch limit must be between 0 and 90 degrees");
    }
    this.pitchLimit = pitchLimit;
    pitch = Math.min(25, pitchLimit);
  }

  public float yaw() {
    return yaw;
  }

  public float pitch() {
    return pitch;
  }

  public float zoom() {
    return zoom;
  }

  public float panX() {
    return panX;
  }

  public float panY() {
    return panY;
  }

  public void rotate(double dx, double dy) {
    requireFinite(dx, dy);
    yaw = (float) ((yaw + dx * .25) % 360);
    pitch = (float) Math.max(-pitchLimit, Math.min(pitchLimit, pitch + dy * .25));
  }

  public void pan(double dx, double dy) {
    requireFinite(dx, dy);
    double x = panX + dx, y = panY + dy;
    if (Math.abs(x) > Float.MAX_VALUE || Math.abs(y) > Float.MAX_VALUE) {
      throw new IllegalArgumentException("Preview pan is outside float range");
    }
    panX = (float) x;
    panY = (float) y;
  }

  public void zoomBy(double scroll) {
    requireFinite(scroll, 0);
    zoom = (float) Math.max(.1, Math.min(8, zoom * Math.pow(1.1, scroll)));
  }

  public void reset() {
    yaw = -135;
    pitch = Math.min(25, pitchLimit);
    zoom = 1;
    panX = 0;
    panY = 0;
  }

  private static void requireFinite(double x, double y) {
    if (!Double.isFinite(x) || !Double.isFinite(y)) {
      throw new IllegalArgumentException("Preview camera input must be finite");
    }
  }
}
