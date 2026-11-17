package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.block.ColoredStatedMachineComponentBlock;
import com.lw.modularmachine.block.StatedMachineComponentBlock;
import com.lw.modularmachine.blockentity.StatedMachineComponentBlockEntity;
import dev.latvian.mods.kubejs.block.BlockBuilder;
import dev.latvian.mods.kubejs.client.ModelGenerator;
import dev.latvian.mods.kubejs.client.VariantBlockStateGenerator;
import dev.latvian.mods.kubejs.generator.KubeAssetGenerator;
import dev.latvian.mods.kubejs.registry.AdditionalObjectRegistry;
import dev.latvian.mods.kubejs.registry.BuilderBase;
import dev.latvian.mods.kubejs.registry.RegistryKubeEvent;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.Context;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;

/**
 * Startup-only declaration; its private KubeJS registry builder is frozen at
 * build.
 */
@ApiStatus.Internal
public final class KubeJSStatedMachineComponentBuilder {
  private final Thread owner = Thread.currentThread();
  private final ResourceLocation id;
  private boolean coloured;
  private boolean built;
  private String texture;

  private KubeJSStatedMachineComponentBuilder(ResourceLocation id) {
    this.id = id;
  }

  public static KubeJSStatedMachineComponentBuilder newBuilder(Context context, String registryName) {
    requireStartup(context);
    return new KubeJSStatedMachineComponentBuilder(KubeJSMachineBuilder.identifier(registryName));
  }

  public KubeJSStatedMachineComponentBuilder setColoured(boolean value) {
    requireEditable();
    coloured = value;
    return this;
  }

  public KubeJSStatedMachineComponentBuilder textureAll(String value) {
    requireEditable();
    texture = ResourceLocation.parse(value).toString();
    return this;
  }

  public void build(Context context) {
    requireStartup(context);
    requireEditable();
    built = true;
    var builder = new RegisteredBlock(id, coloured, texture);
    var registration = new RegistryKubeEvent<Block>(Registries.BLOCK);
    registration.add(Registries.BLOCK, builder);
    builder.createAdditionalObjects(registration);
  }

  private void requireEditable() {
    if (built || Thread.currentThread() != owner) {
      throw new IllegalStateException("Stated component is built or used outside its startup thread");
    }
  }

  private static void requireStartup(Context context) {
    if (!(context instanceof KubeJSContext kube) || kube.getType() != ScriptType.STARTUP) {
      throw new IllegalStateException("Stated machine components require startup_scripts and a full restart");
    }
  }

  private static final class RegisteredBlock extends BlockBuilder {
    private final boolean coloured;
    private BlockEntityType<?> entityType;

    RegisteredBlock(ResourceLocation id, boolean coloured, String texture) {
      super(id);
      this.coloured = coloured;
      if (texture != null) {
        baseTexture = texture;
      }
      hardness(2);
      resistance(10);
      soundType(SoundType.METAL);
      defaultCutout();
    }

    @Override
    public Block createObject() {
      return coloured ? new ColoredStatedMachineComponentBlock(
          createProperties(),
          () -> entityType) : new StatedMachineComponentBlock(createProperties());
    }

    @Override
    public void createAdditionalObjects(AdditionalObjectRegistry registration) {
      super.createAdditionalObjects(registration);
      if (coloured) {
        registration.add(Registries.BLOCK_ENTITY_TYPE, new BuilderBase<BlockEntityType<?>>(id) {
          @Override
          public BlockEntityType<?> createObject() {
            entityType = BlockEntityType.Builder.of(
                (position, state) -> new StatedMachineComponentBlockEntity(entityType, position, state),
                RegisteredBlock.this.get()).build(null);
            return entityType;
          }
        });
      }
    }

    @Override
    protected void generateBlockState(VariantBlockStateGenerator states) {
      for (var state : StatedMachineComponentBlock.WorkingState.values()) {
        states.simpleVariant(
            "working_state=" + state.getSerializedName(),
            id.withPath(path -> "block/" + path + "_" + state.getSerializedName()));
      }
    }

    @Override
    protected void generateBlockModels(KubeAssetGenerator generator) {
      generator.blockModel(id, model -> {
        model.parent(ResourceLocation.withDefaultNamespace("block/cube_all"));
        model.texture("all", baseTexture);
        if (coloured) {
          model.element(element -> {
            element.size(new AABB(0, 0, 0, 16, 16, 16));
            element.allFaces(face -> {
              face.tex("#all");
              face.cull();
              face.tintindex(0);
            });
          });
        }
      });
      for (var state : StatedMachineComponentBlock.WorkingState.values()) {
        generator
            .blockModel(id.withSuffix("_" + state.getSerializedName()), model -> model.parent(id.withPrefix("block/")));
      }
    }

    @Override
    protected void generateItemModel(ModelGenerator model) {
      model.parent(id.withPath(path -> "block/" + path + "_idle"));
    }
  }
}
