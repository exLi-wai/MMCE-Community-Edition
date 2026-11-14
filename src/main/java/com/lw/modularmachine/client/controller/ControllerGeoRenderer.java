package com.lw.modularmachine.client.controller;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.client.controller.ControllerClientEvents;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.client.appearance.MachineVisibilityClient;
import com.lw.modularmachine.integration.kubejs.KubeJSControllerClientRegistration;
import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Internal optional renderer. The core block entities never implement GeckoLib
 * contracts.
 */
@ApiStatus.Internal
public final class ControllerGeoRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
  private static final Map<BlockEntity, Visual> VISUALS = new WeakHashMap<>();
  private static final Set<KubeJSControllerClientRegistration.Model> WARNED = new HashSet<>();

  public static void register(EntityRenderersEvent.RegisterRenderers event) {
    event.registerBlockEntityRenderer(
        MachineRegistries.CONTROLLER_ENTITY.get(),
        ignored -> new ControllerGeoRenderer<>());
    event.registerBlockEntityRenderer(
        MachineRegistries.FACTORY_CONTROLLER_ENTITY.get(),
        ignored -> new ControllerGeoRenderer<>());
    ControllerClientPublisher.setRendererInvalidation(() -> {
      VISUALS.clear();
      WARNED.clear();
    });
    NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post value) -> tick(value));
  }

  private static void tick(ClientTickEvent.Post event) {
    var level = Minecraft.getInstance().level;
    var entries = VISUALS.keySet().iterator();
    while (entries.hasNext()) {
      var entity = entries.next();
      if (level == null
          || entity.isRemoved()
          || entity.getLevel() != level
          || !level.hasChunkAt(entity.getBlockPos())
          || level.getBlockEntity(entity.getBlockPos()) != entity) {
        ControllerClientPublisher.modelRendered(entity, false);
        entries.remove();
      }
    }
  }

  @Override
  public boolean shouldRenderOffScreen(T entity) {
    return ControllerClientPublisher.hasPublishedModel(entity);
  }

  @Override
  public int getViewDistance() {
    return ControllerClientPublisher.MODEL_VIEW_DISTANCE;
  }

  @Override
  public boolean shouldRender(T entity, Vec3 cameraPosition) {
    return ControllerClientPublisher.shouldRenderModel(entity, cameraPosition);
  }

  @Override
  public void render(T entity, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
    var level = entity.getLevel();
    var state = entity.getBlockState();
    if (level == null
        || !level.isClientSide
        || entity.isRemoved()
        || state.hasProperty(ControllerAppearance.HIDDEN) && state.getValue(ControllerAppearance.HIDDEN)
        || MachineVisibilityClient.isComponentHidden(entity.getBlockPos())) {
      ControllerClientPublisher.modelRendered(entity, false);
      return;
    }
    var selected = ControllerClientPublisher.selectModel(entity);
    if (selected.isEmpty()) {
      VISUALS.remove(entity);
      return;
    }
    var model = selected.orElseThrow();
    var visual = VISUALS.get(entity);
    if (visual == null || !visual.registration.equals(model)) {
      visual = new Visual(model);
      VISUALS.put(entity, visual);
    }
    if (visual.failed) {
      ControllerClientPublisher.modelRendered(entity, false);
      return;
    }
    var renderPoses = new PoseStack();
    renderPoses.last().pose().set(poses.last().pose());
    renderPoses.last().normal().set(poses.last().normal());
    try {
      visual.update(entity, level.getGameTime());
      visual.model.getBakedModel(model.geometry());
      if (!GeckoLibCache.getBakedAnimations().containsKey(model.animation())) {
        throw new IllegalArgumentException("Controller animation resource was not loaded by GeckoLib");
      }
      var orientation = state.getBlock() instanceof FactoryControllerBlock ? FactoryControllerBlock
          .orientationOf(state) : MachineControllerBlock.orientationOf(state);
      renderPoses.translate(.5, .5, .5);
      renderPoses.mulPose(rotation(orientation));
      renderPoses.translate(-.5, -.5, -.5);
      var type = RenderType.entityCutoutNoCull(model.texture());
      visual.renderer.render(renderPoses, visual, buffers, type, buffers.getBuffer(type), light, partialTick);
      ControllerClientPublisher.modelRendered(entity, true);
    } catch (RuntimeException invalid) {
      visual.failed = true;
      ControllerClientPublisher.modelRendered(entity, false);
      if (WARNED.add(model)) {
        ModularMachine.LOGGER
            .warn("Controller model {} could not render; using the baked model", model.name(), invalid);
      }
    }
  }

  private static Quaternionf rotation(MachineOrientation orientation) {
    var right = orientation.right();
    var up = orientation.up();
    var back = orientation.back();
    return new Quaternionf().setFromNormalized(
        new Matrix3f(
            right.getStepX(),
            right.getStepY(),
            right.getStepZ(),
            up.getStepX(),
            up.getStepY(),
            up.getStepZ(),
            back.getStepX(),
            back.getStepY(),
            back.getStepZ()));
  }

  /**
   * Pure per-instance animation state; holding this value cannot keep a block
   * entity or world alive.
   */
  private static final class Visual implements GeoAnimatable {
    private final KubeJSControllerClientRegistration.Model registration;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this, false);
    private final Model model = new Model();
    private final Renderer renderer = new Renderer(model);
    private List<ControllerClientEvents.Animation> stages = List.of();
    private RawAnimation animation;
    private long tick = Long.MIN_VALUE;
    private int transition = 5;
    private double speed = 1;
    private boolean stopped = true, pending, completed, failed;

    private Visual(KubeJSControllerClientRegistration.Model registration) {
      this.registration = registration;
    }

    private void update(BlockEntity entity, long currentTick) {
      if (tick == currentTick) {
        return;
      }
      tick = currentTick;
      var proposal = ControllerClientPublisher.animation(entity, registration.name());
      var next = proposal.animations();
      boolean nextStopped = proposal.getPlayState() == 1 || next.isEmpty();
      transition = proposal.getTransitionLengthTicks();
      speed = proposal.getAnimationSpeed();
      if (!stages.equals(next) || stopped != nextStopped) {
        stages = next;
        stopped = nextStopped;
        animation = RawAnimation.begin();
        for (var stage : stages) {
          if (model.getAnimation(this, stage.name()) == null) {
            throw new IllegalArgumentException("Unknown controller animation " + stage.name());
          }
          animation = stage.loop() ? animation.thenLoop(stage.name()) : animation.thenPlay(stage.name());
        }
        pending = !stopped;
        completed = false;
      }
    }

    private PlayState animate(AnimationState<Visual> state) {
      var controller = state.getController();
      controller.transitionLength(transition).setAnimationSpeed(speed);
      if (stopped || completed || animation == null) {
        return PlayState.STOP;
      }
      if (pending) {
        controller.forceAnimationReset();
        state.setAnimation(animation);
        pending = false;
      } else if (controller.hasAnimationFinished()) {
        completed = true;
        return PlayState.STOP;
      } else {
        state.setAnimation(animation);
      }
      return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
      controllers.add(new AnimationController<>(this, "controller", 5, this::animate));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
      return cache;
    }

    @Override
    public double getTick(Object relatedObject) {
      return tick;
    }
  }

  private static final class Model extends GeoModel<Visual> {
    @Override
    public ResourceLocation getModelResource(Visual visual) {
      return visual.registration.geometry();
    }

    @Override
    public ResourceLocation getTextureResource(Visual visual) {
      return visual.registration.texture();
    }

    @Override
    public ResourceLocation getAnimationResource(Visual visual) {
      return visual.registration.animation();
    }
  }

  private static final class Renderer extends GeoObjectRenderer<Visual> {
    private Renderer(Model model) {
      super(model);
    }

    @Override
    public void preRender(PoseStack poses, Visual visual, BakedGeoModel model, MultiBufferSource buffers, VertexConsumer vertices, boolean rerender, float partialTick, int light, int overlay, int color) {
      objectRenderTranslations = new Matrix4f(poses.last().pose());
      scaleModelForRender(scaleWidth, scaleHeight, poses, visual, model, rerender, partialTick, light, overlay);
      poses.translate(.5, 0, .5);
    }
  }
}
