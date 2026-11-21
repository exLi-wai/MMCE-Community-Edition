package com.lw.modularmachine.integration.athena;

import com.lw.modularmachine.ModularMachine;
import earth.terrarium.athena.api.client.neoforge.WrappedGetter;
import earth.terrarium.athena.api.client.utils.CtmState;
import earth.terrarium.athena.api.client.utils.CtmUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.ApiStatus;

/**
 * Optional layered CTM. No optional Athena class is reached until client model
 * baking.
 */
@ApiStatus.Internal
public final class AthenaConnectedTextures {
  private static final Direction[] FACES = Direction.values();
  private static final Layer[] LAYERS = Layer.values();
  private static final String[] VARIANTS = {"particle", "empty", "center", "vertical", "horizontal"};
  private static final ModelProperty<int[][]> CONNECTIONS = new ModelProperty<>();

  enum Layer {
    CASING("blockcasing_plain", "machine_casing_plain"),
    CIRCUITRY("overlay/casings/overlay_circuitry", "machine_casing_circuitry"),
    FIREBOX("overlay/casings/overlay_firebox", "machine_casing_firebox"),
    GEARBOX("overlay/casings/overlay_gearbox", "machine_casing_gearbox"),
    REINFORCED("overlay/casings/overlay_reinforced", "machine_casing_reinforced"),
    VENT("overlay/casings/overlay_vent", "machine_casing_vent"),
    ENVIRONMENT("overlay/interfaces/overlay_environmenthatch", "environment_hatch"),
    LIFE_ESSENCE_WINDOW("ctm/life_essence_window/particle", "life_essence_window");

    final ResourceLocation original;
    final String directory;
    final int bit = 1 << ordinal();

    Layer(String original, String directory) {
      this.original = id("block/" + original);
      this.directory = directory;
    }
  }

  private record MaterialSet(Layer layer, TextureAtlasSprite[] variants) {
  }

  private record Face(int layers, Map<BakedQuad, AthenaQuadVariants> quads) {
  }

  private AthenaConnectedTextures() {
  }

  public static void wrapModels(ModelEvent.ModifyBakingResult event) {
    var materials = loadMaterials(event);
    if (materials.isEmpty()) {
      return;
    }
    var templates = new HashMap<AthenaQuadVariants.Key, AthenaQuadVariants>();
    var inspected = new IdentityHashMap<BakedModel, Face[]>();
    var surfaces = new IdentityHashMap<BlockState, Face[]>();
    var models = event.getModels();
    for (var block : BuiltInRegistries.BLOCK) {
      if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(ModularMachine.MODID)) {
        continue;
      }
      for (var state : block.getStateDefinition().getPossibleStates()) {
        var location = BlockModelShaper.stateToModelLocation(state);
        var model = models.get(location);
        if (!(model instanceof SimpleBakedModel)) {
          continue;
        }
        var faces = inspected.get(model);
        if (!inspected.containsKey(model)) {
          faces = bakeFaces(model, state, materials, templates);
          inspected.put(model, faces);
        }
        if (faces != null) {
          surfaces.put(state, faces);
        }
      }
    }
    var coverage = Map.copyOf(surfaces);
    for (var entry : surfaces.entrySet()) {
      var location = BlockModelShaper.stateToModelLocation(entry.getKey());
      var original = models.get(location);
      models.put(location, new ConnectedModel(original, entry.getValue(), coverage));
    }
  }

  private static Map<ResourceLocation, MaterialSet> loadMaterials(ModelEvent.ModifyBakingResult event) {
    var result = new HashMap<ResourceLocation, MaterialSet>();
    for (var layer : LAYERS) {
      var sprites = new TextureAtlasSprite[VARIANTS.length];
      sprites[0] = event.getTextureGetter().apply(new Material(InventoryMenu.BLOCK_ATLAS, layer.original));
      boolean present = sprites[0].contents().name().equals(layer.original);
      for (int i = 1; i < VARIANTS.length; i++) {
        var texture = id("block/ctm/" + layer.directory + "/" + VARIANTS[i]);
        sprites[i] = event.getTextureGetter().apply(new Material(InventoryMenu.BLOCK_ATLAS, texture));
        present &= sprites[i].contents().name().equals(texture);
      }
      if (present) {
        result.put(layer.original, new MaterialSet(layer, sprites));
      }
    }

    return result;
  }

  private static Face[] bakeFaces(BakedModel model, BlockState state, Map<ResourceLocation, MaterialSet> materials, Map<AthenaQuadVariants.Key, AthenaQuadVariants> templates) {
    var faces = new Face[FACES.length];
    var random = RandomSource.create(0);
    boolean any = false;
    for (var direction : FACES) {
      var quads = new IdentityHashMap<BakedQuad, AthenaQuadVariants>();
      int layers = 0;
      for (var quad : model.getQuads(state, direction, random, ModelData.EMPTY, null)) {
        var material = materials.get(quad.getSprite().contents().name());
        if (material == null) {
          continue;
        }
        var key = AthenaQuadVariants.capture(quad, material.layer(), direction);
        if (key == null) {
          continue;
        }
        quads.put(quad, templates.computeIfAbsent(key, once -> new AthenaQuadVariants(once, material.variants())));
        layers |= material.layer().bit;
      }
      if (layers != 0) {
        faces[direction.ordinal()] = new Face(layers, Map.copyOf(quads));
        any = true;
      }
    }

    return any ? faces : null;
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, path);
  }

  /**
   * Geometry and adjacency eligibility belong to the current baked model
   * generation.
   */
  private static final class ConnectedModel extends BakedModelWrapper<BakedModel> {
    private final Face[] faces;
    private final Map<BlockState, Face[]> coverage;

    ConnectedModel(BakedModel original, Face[] faces, Map<BlockState, Face[]> coverage) {
      super(original);
      this.faces = faces;
      this.coverage = coverage;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
      var resolved = super.getModelData(level, pos, state, data);
      var view = new WrappedGetter(level);
      int[][] connections = null;
      for (var direction : FACES) {
        int side = direction.ordinal();
        var face = faces[side];
        if (face == null) {
          continue;
        }
        int[] variants = computeConnections(view, pos, state, direction, face);
        if (variants != null) {
          if (connections == null) {
            connections = new int[FACES.length][];
          }
          connections[side] = variants;
        }
      }

      return connections == null ? resolved : resolved.derive().with(CONNECTIONS, connections).build();
    }

    private int[] computeConnections(WrappedGetter view, BlockPos pos, BlockState state, Direction direction, Face face) {
      int[] variants = null;
      for (var layer : LAYERS) {
        if ((face.layers() & layer.bit) == 0) {
          continue;
        }
        var stateCtm = CtmState.from(view, state, pos, direction, (neighbor, raw, appearance) -> {
          var neighborFaces = coverage.get(appearance);
          if (neighborFaces == null) {
            return false;
          }
          var adjacent = neighborFaces[direction.ordinal()];
          return adjacent != null && (adjacent.layers() & layer.bit) != 0;
        });
        if (stateCtm.up() || stateCtm.down() || stateCtm.left() || stateCtm.right()) {
          if (variants == null) {
            variants = new int[LAYERS.length];
          }
          variants[layer.ordinal()] = tileSelection(stateCtm);
        }
      }
      return variants;
    }

    private static int tileSelection(CtmState state) {
      return CtmUtils.getTexture(state.up(), state.left(), state.upLeft()) | CtmUtils
          .getTexture(state.up(), state.right(), state.upRight()) << 3 | CtmUtils
              .getTexture(state.down(), state.left(), state.downLeft()) << 6 | CtmUtils
                  .getTexture(state.down(), state.right(), state.downRight()) << 9;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random, ModelData data, RenderType renderType) {
      var original = super.getQuads(state, direction, random, data, renderType);
      var connections = data.get(CONNECTIONS);
      if (direction == null || connections == null || original.isEmpty()) {
        return original;
      }
      int side = direction.ordinal();
      int[] selections = connections[side];
      if (selections == null || faces[side] == null) {
        return original;
      }
      ArrayList<BakedQuad> result = null;
      for (int i = 0; i < original.size(); i++) {
        var quad = original.get(i);
        var variant = faces[side].quads().get(quad);
        int tiles = variant == null ? 0 : selections[variant.layer().ordinal()];
        if (tiles == 0) {
          if (result != null) {
            result.add(quad);
          }
        } else {
          if (result == null) {
            result = new ArrayList<>(original.size() + 3);
            result.addAll(original.subList(0, i));
          }
          variant.append(tiles, result);
        }
      }

      return result == null ? original : result;
    }
  }
}
