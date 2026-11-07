package com.lw.modularmachine.integration.emi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * EMI's Widget API does not forward dragging, release or scroll events. Route
 * screen events only to previews rendered on the current page.
 */
final class EmiPreviewInput {
  private record Target(EmiStructurePreviewWidget widget, Matrix4f inverse, long frame) {
    Vector3f local(double x, double y) {
      return inverse.transformPosition(new Vector3f((float) x, (float) y, 0));
    }
  }

  private static final Map<EmiStructurePreviewWidget, Target> VISIBLE = new LinkedHashMap<>();
  private static boolean registered;
  private static Screen owner;
  private static EmiStructurePreviewWidget active;
  private static long frame;

  private EmiPreviewInput() {
  }

  static synchronized void register() {
    if (registered) {
      return;
    }
    registered = true;
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, EmiPreviewInput::mousePressed);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, EmiPreviewInput::mouseDragged);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, EmiPreviewInput::mouseReleased);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, EmiPreviewInput::mouseScrolled);
    NeoForge.EVENT_BUS.addListener(EmiPreviewInput::beforeRender);
    NeoForge.EVENT_BUS.addListener(EmiPreviewInput::afterRender);
    NeoForge.EVENT_BUS.addListener(EmiPreviewInput::closing);
    NeoForge.EVENT_BUS.addListener(EmiPreviewInput::initializing);
    NeoForge.EVENT_BUS.addListener(EmiPreviewInput::loggingOut);
  }

  static void rendered(EmiStructurePreviewWidget widget, Matrix4f transform) {
    var screen = Minecraft.getInstance().screen;
    if (screen == null) {
      return;
    }
    if (owner != screen) {
      clear();
      owner = screen;
    }
    VISIBLE.put(widget, new Target(widget, new Matrix4f(transform).invert(), frame));
  }

  private static void beforeRender(ScreenEvent.Render.Pre event) {
    if (owner == event.getScreen()) {
      frame++;
    }
  }

  private static void afterRender(ScreenEvent.Render.Post event) {
    if (owner != event.getScreen()) {
      return;
    }
    var iterator = VISIBLE.values().iterator();
    while (iterator.hasNext()) {
      var target = iterator.next();
      if (target.frame() != frame) {
        if (active == target.widget()) {
          active = null;
        }
        target.widget().release();
        iterator.remove();
      }
    }
  }

  private static boolean owns(Screen screen) {
    return owner == screen && Minecraft.getInstance().screen == screen;
  }

  private static void mousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
    if (!owns(event.getScreen())) {
      return;
    }
    if (active != null) {
      active.stopDragging();
      active = null;
    }
    // Reverse drawing order so overlapping previews agree with the visible page.
    var targets = new ArrayList<>(VISIBLE.values());
    for (var target : targets.reversed()) {
      var local = target.local(event.getMouseX(), event.getMouseY());
      if (target.widget().mouseClicked((int) Math.floor(local.x), (int) Math.floor(local.y), event.getButton())) {
        if (target.widget().isDragging()) {
          active = target.widget();
        }
        event.setCanceled(true);
        return;
      }
    }
  }

  private static void mouseDragged(ScreenEvent.MouseDragged.Pre event) {
    if (!owns(event.getScreen()) || active == null) {
      return;
    }
    var target = VISIBLE.get(active);
    if (target == null) {
      active.stopDragging();
      active = null;
      return;
    }
    var delta = target.inverse()
        .transformDirection(new Vector3f((float) event.getDragX(), (float) event.getDragY(), 0));
    if (active.drag(event.getMouseButton(), delta.x, delta.y)) {
      event.setCanceled(true);
    }
  }

  private static void mouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
    if (owns(event.getScreen()) && active != null && active.releaseButton(event.getButton())) {
      active = null;
      event.setCanceled(true);
    }
  }

  private static void mouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
    if (!owns(event.getScreen())) {
      return;
    }
    var targets = new ArrayList<>(VISIBLE.values());
    for (var target : targets.reversed()) {
      var local = target.local(event.getMouseX(), event.getMouseY());
      if (target.widget().scroll(local.x, local.y, event.getScrollDeltaY())) {
        event.setCanceled(true);
        return;
      }
    }
  }

  private static void closing(ScreenEvent.Closing event) {
    if (owner == event.getScreen()) {
      clear();
    }
  }

  private static void initializing(ScreenEvent.Init.Pre event) {
    if (owner == event.getScreen()) {
      clear();
    }
  }

  private static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
    clear();
  }

  private static void clear() {
    VISIBLE.keySet().forEach(EmiStructurePreviewWidget::release);
    VISIBLE.clear();
    active = null;
    owner = null;
  }
}
