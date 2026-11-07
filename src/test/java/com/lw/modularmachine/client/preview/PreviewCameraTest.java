package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreviewCameraTest {
  @Test
  void rotationAndZoomRespectPerViewerLimits() {
    var camera = new PreviewCamera(89.9F);
    camera.rotate(40, 1000);
    camera.zoomBy(100);

    assertEquals(-125, camera.yaw());
    assertEquals(89.9F, camera.pitch(), .0001F);
    assertEquals(8F, camera.zoom());

    camera.zoomBy(-100);
    assertEquals(.1F, camera.zoom(), .0001F);
  }

  @Test
  void panAndResetRetainOnlyCameraState() {
    var camera = new PreviewCamera();
    camera.rotate(20, -10);
    camera.pan(4, -7);
    camera.zoomBy(2);

    assertEquals(4F, camera.panX());
    assertEquals(-7F, camera.panY());
    camera.reset();

    assertEquals(-135F, camera.yaw());
    assertEquals(25F, camera.pitch());
    assertEquals(1F, camera.zoom());
    assertEquals(0F, camera.panX());
    assertEquals(0F, camera.panY());
  }

  @Test
  void rejectsInvalidPitchAndInput() {
    assertThrows(IllegalArgumentException.class, () -> new PreviewCamera(Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> new PreviewCamera(91));

    var levelCamera = new PreviewCamera(0);
    assertEquals(0F, levelCamera.pitch());
    levelCamera.rotate(0, 100);
    assertEquals(0F, levelCamera.pitch());
    levelCamera.reset();
    assertEquals(0F, levelCamera.pitch());

    var camera = new PreviewCamera();
    assertThrows(IllegalArgumentException.class, () -> camera.rotate(Double.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> camera.pan(0, Double.POSITIVE_INFINITY));
  }
}
