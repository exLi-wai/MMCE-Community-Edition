package com.lw.modularmachine.api.client.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-only visual proposals. No world, block entity, inventory or graphics
 * reference is exposed.
 */
public final class ControllerClientEvents {
  private ControllerClientEvents() {
  }

  public record Context(
      ResourceLocation machine,
      ResourceLocation dimension,
      BlockPos position,
      boolean factory,
      boolean formed,
      String appearance,
      long tick,
      String customData) {
    public Context {
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(dimension, "dimension");
      position = Objects.requireNonNull(position, "position").immutable();
      Objects.requireNonNull(appearance, "appearance");
      Objects.requireNonNull(customData, "customData");
    }

    public boolean isWorking() {
      return "running".equals(appearance);
    }
  }

  @FunctionalInterface
  public interface GuiListener {
    void render(GuiEvent event);
  }

  @FunctionalInterface
  public interface ModelListener {
    void select(ModelEvent event);
  }

  @FunctionalInterface
  public interface AnimationListener {
    void animate(AnimationEvent event);
  }

  public static final class GuiEvent {
    private final Context controller;
    private final List<String> extraInfo = new ArrayList<>();

    public GuiEvent(Context controller) {
      this.controller = Objects.requireNonNull(controller, "controller");
    }

    public Context getController() {
      return controller;
    }

    public List<String> getExtraInfo() {
      return List.copyOf(extraInfo);
    }

    public void setExtraInfo(List<String> lines) {
      var validated = List.copyOf(lines);
      if (validated.size() > 32 || validated.stream().anyMatch(line -> line.length() > 512)) {
        throw new IllegalArgumentException("Controller GUI info exceeds its line or text budget");
      }
      extraInfo.clear();
      extraInfo.addAll(validated);
    }

    public void addInfo(String text) {
      if (extraInfo.size() >= 32 || text == null || text.length() > 512) {
        throw new IllegalArgumentException("Controller GUI info exceeds its line or text budget");
      }
      extraInfo.add(text);
    }
  }

  public static final class ModelEvent {
    private final Context controller;
    private String modelName;

    public ModelEvent(Context controller, String modelName) {
      this.controller = Objects.requireNonNull(controller, "controller");
      this.modelName = modelName;
    }

    public Context getController() {
      return controller;
    }

    public String getModelName() {
      return modelName;
    }

    public void setModelName(String name) {
      modelName = requireName(name);
    }
  }

  public record Animation(String name, boolean loop) {
    public Animation {
      name = requireName(name);
    }
  }

  public static final class AnimationEvent {
    private final Context controller;
    private final String currentModelName;
    private final List<Animation> animations = new ArrayList<>();
    private int transitionLengthTicks = 5;
    private int playState;
    private double animationSpeed = 1;

    public AnimationEvent(Context controller, String modelName) {
      this.controller = Objects.requireNonNull(controller, "controller");
      currentModelName = requireName(modelName);
    }

    public Context getController() {
      return controller;
    }

    public String getCurrentModelName() {
      return currentModelName;
    }

    public int getTransitionLengthTicks() {
      return transitionLengthTicks;
    }

    public void setTransitionLengthTicks(int ticks) {
      if (ticks < 0 || ticks > 1200) {
        throw new IllegalArgumentException("Animation transition requires 0..1200 ticks");
      }
      transitionLengthTicks = ticks;
    }

    public int getPlayState() {
      return playState;
    }

    public void setPlayState(int value) {
      if (value != 0 && value != 1) {
        throw new IllegalArgumentException("Animation play state requires 0 (play) or 1 (stop)");
      }
      playState = value;
    }

    public double getAnimationSpeed() {
      return animationSpeed;
    }

    public void setAnimationSpeed(double speed) {
      if (!Double.isFinite(speed) || speed < 0 || speed > 1024) {
        throw new IllegalArgumentException("Invalid animation speed");
      }
      animationSpeed = speed;
    }

    public void addAnimation(String name) {
      addAnimation(name, false);
    }

    public void addAnimation(String name, boolean loop) {
      if (animations.size() >= 32) {
        throw new IllegalArgumentException("Too many controller animation stages");
      }
      animations.add(new Animation(name, loop));
    }

    public void setAnimation(String name) {
      setAnimation(name, false);
    }

    public void setAnimation(String name, boolean loop) {
      var animation = new Animation(name, loop);
      animations.clear();
      animations.add(animation);
    }

    public List<Animation> animations() {
      return List.copyOf(animations);
    }
  }

  public static String requireName(String name) {
    if (name == null || name.isBlank() || name.length() > 256) {
      throw new IllegalArgumentException("Invalid controller visual name");
    }
    return name;
  }
}
