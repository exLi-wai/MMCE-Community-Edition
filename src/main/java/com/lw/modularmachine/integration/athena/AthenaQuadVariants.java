package com.lw.modularmachine.integration.athena;

import static net.neoforged.neoforge.client.model.IQuadTransformer.COLOR;
import static net.neoforged.neoforge.client.model.IQuadTransformer.NORMAL;
import static net.neoforged.neoforge.client.model.IQuadTransformer.POSITION;
import static net.neoforged.neoforge.client.model.IQuadTransformer.STRIDE;
import static net.neoforged.neoforge.client.model.IQuadTransformer.UV0;
import static net.neoforged.neoforge.client.model.IQuadTransformer.UV2;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Bakes the four face quadrants without changing the original geometry's layer,
 * tint or light.
 */
final class AthenaQuadVariants {
  private static final int FULLY_CONNECTED = 1 | 1 << 3 | 1 << 6 | 1 << 9;
  private static final float POSITION_EPSILON = 0.001F;
  private static final float UV_EPSILON = 0.005F;

  record Key(
      AthenaConnectedTextures.Layer layer,
      Direction face,
      Direction direction,
      float depth,
      float minU,
      float maxU,
      float minV,
      float maxV,
      int winding,
      UvOrientation uv,
      int tint,
      int color,
      int light,
      int normal,
      boolean shade,
      boolean ambientOcclusion) {
  }

  record UvOrientation(boolean swapsAxes, boolean flipU, boolean flipV) {
    float u(float horizontal, float vertical) {
      float value = swapsAxes ? vertical : horizontal;
      return flipU ? 1F - value : value;
    }

    float v(float horizontal, float vertical) {
      float value = swapsAxes ? horizontal : vertical;
      return flipV ? 1F - value : value;
    }

    int sprite(int tile) {
      if (!swapsAxes) {
        return tile;
      }
      return switch (tile) {
        case 3 -> 4;
        case 4 -> 3;
        default -> tile;
      };
    }
  }

  private final AthenaConnectedTextures.Layer layer;
  private final BakedQuad[][] quadrants = new BakedQuad[4][];
  private final BakedQuad connected;

  AthenaQuadVariants(Key key, TextureAtlasSprite[] sprites) {
    layer = key.layer();
    connected = bake(key, sprites[1], 0F, 1F, 0F, 1F);
    for (int q = 0; q < quadrants.length; q++) {
      float u = (q & 1) * .5F;
      float v = (q >> 1) * .5F;
      quadrants[q] = new BakedQuad[sprites.length];
      for (int i = 0; i < sprites.length; i++) {
        quadrants[q][i] = bake(key, sprites[key.uv().sprite(i)], u, u + .5F, v, v + .5F);
      }
    }
  }

  AthenaConnectedTextures.Layer layer() {
    return layer;
  }

  void append(int tiles, List<BakedQuad> output) {
    if (tiles == FULLY_CONNECTED) {
      output.add(connected);
      return;
    }
    for (int q = 0; q < quadrants.length; q++) {
      output.add(quadrants[q][tiles & 7]);
      tiles >>>= 3;
    }
  }

  /**
   * Only full, axis-aligned faces with a complete sprite mapping can be stitched.
   */
  static Key capture(BakedQuad quad, AthenaConnectedTextures.Layer layer, Direction face) {
    var vertices = quad.getVertices();
    if (vertices.length != 4 * STRIDE) {
      return null;
    }
    Direction direction = quad.getDirection();
    // Both sides of a transparent window use the neighbors of its physical face.
    if (direction != face
        && !(layer == AthenaConnectedTextures.Layer.LIFE_ESSENCE_WINDOW && direction == face.getOpposite())) {
      return null;
    }
    float depth = alongFace(vertices, 0, face);
    float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1F : 0F;
    if (!near(depth, boundary, POSITION_EPSILON)) {
      return null;
    }
    float minU = Float.POSITIVE_INFINITY;
    float minV = Float.POSITIVE_INFINITY;
    float maxU = Float.NEGATIVE_INFINITY;
    float maxV = Float.NEGATIVE_INFINITY;
    int color = vertices[COLOR];
    int light = vertices[UV2];
    int normal = vertices[NORMAL];
    for (int i = 0; i < vertices.length; i += STRIDE) {
      if (vertices[i + COLOR] != color
          || vertices[i + UV2] != light
          || vertices[i + NORMAL] != normal
          || !near(alongFace(vertices, i, face), depth, POSITION_EPSILON)) {
        return null;
      }
      float u = faceU(vertices, i, face);
      float v = faceV(vertices, i, face);
      minU = Math.min(minU, u);
      minV = Math.min(minV, v);
      maxU = Math.max(maxU, u);
      maxV = Math.max(maxV, v);
    }
    if (!near(minU, 0F, POSITION_EPSILON)
        || !near(minV, 0F, POSITION_EPSILON)
        || !near(maxU, 1F, POSITION_EPSILON)
        || !near(maxV, 1F, POSITION_EPSILON)) {
      return null;
    }

    var sprite = quad.getSprite();
    int winding = 0;
    int visited = 0;
    int uvByPosition = 0;
    float lowUv = Mth.lerp(sprite.uvShrinkRatio(), 0F, .5F);
    float highUv = Mth.lerp(sprite.uvShrinkRatio(), 1F, .5F);
    for (int i = 0; i < vertices.length; i += STRIDE) {
      float u = faceU(vertices, i, face);
      float v = faceV(vertices, i, face);
      boolean right = near(u, maxU, POSITION_EPSILON);
      boolean bottom = near(v, maxV, POSITION_EPSILON);
      if ((!right && !near(u, minU, POSITION_EPSILON)) || (!bottom && !near(v, minV, POSITION_EPSILON))) {
        return null;
      }
      float sampleU = sprite.getUOffset(read(vertices, i + UV0));
      float sampleV = sprite.getVOffset(read(vertices, i + UV0 + 1));
      boolean textureRight = near(sampleU, highUv, UV_EPSILON);
      boolean textureBottom = near(sampleV, highUv, UV_EPSILON);
      if ((!textureRight && !near(sampleU, lowUv, UV_EPSILON))
          || (!textureBottom && !near(sampleV, lowUv, UV_EPSILON))) {
        return null;
      }
      int corner = (right ? 1 : 0) | (bottom ? 2 : 0);
      int uvCorner = (textureRight ? 1 : 0) | (textureBottom ? 2 : 0);
      winding |= corner << (i / STRIDE * 2);
      visited |= 1 << corner;
      uvByPosition |= uvCorner << (corner * 2);
    }
    if (visited != 15) {
      return null;
    }
    int origin = uvByPosition & 3;
    int across = (uvByPosition >> 2 & 3) ^ origin;
    int down = (uvByPosition >> 4 & 3) ^ origin;
    if (!(across == 1 && down == 2 || across == 2 && down == 1) || uvByPosition >> 6 != (origin ^ 3)) {
      return null;
    }
    return new Key(
        layer,
        face,
        direction,
        depth,
        minU,
        maxU,
        minV,
        maxV,
        winding,
        new UvOrientation(across == 2, (origin & 1) != 0, (origin & 2) != 0),
        quad.getTintIndex(),
        color,
        light,
        normal,
        quad.isShade(),
        quad.hasAmbientOcclusion());
  }

  private static BakedQuad bake(Key key, TextureAtlasSprite sprite, float left, float right, float top, float bottom) {
    int[] vertices = new int[4 * STRIDE];
    for (int vertex = 0; vertex < 4; vertex++) {
      int corner = key.winding() >> (2 * vertex);
      float u = (corner & 1) == 0 ? left : right;
      float v = (corner & 2) == 0 ? top : bottom;
      int i = vertex * STRIDE;
      place(
          vertices,
          i,
          key.face(),
          key.depth(),
          Mth.lerp(u, key.minU(), key.maxU()),
          Mth.lerp(v, key.minV(), key.maxV()));
      vertices[i + UV0] = bits(sprite.getU(Mth.lerp(sprite.uvShrinkRatio(), key.uv().u(u, v), .5F)));
      vertices[i + UV0 + 1] = bits(sprite.getV(Mth.lerp(sprite.uvShrinkRatio(), key.uv().v(u, v), .5F)));
      vertices[i + COLOR] = key.color();
      vertices[i + UV2] = key.light();
      vertices[i + NORMAL] = key.normal();
    }
    return new BakedQuad(vertices, key.tint(), key.direction(), sprite, key.shade(), key.ambientOcclusion());
  }

  private static float alongFace(int[] vertices, int index, Direction direction) {
    return read(vertices, index + POSITION + direction.getAxis().ordinal());
  }

  private static float faceU(int[] vertices, int index, Direction direction) {
    int i = index + POSITION;
    return switch (direction) {
      case DOWN, UP, SOUTH -> read(vertices, i);
      case NORTH -> 1F - read(vertices, i);
      case WEST -> read(vertices, i + 2);
      case EAST -> 1F - read(vertices, i + 2);
    };
  }

  private static float faceV(int[] vertices, int index, Direction direction) {
    int i = index + POSITION;
    return switch (direction) {
      case DOWN -> 1F - read(vertices, i + 2);
      case UP -> read(vertices, i + 2);
      case NORTH, SOUTH, WEST, EAST -> 1F - read(vertices, i + 1);
    };
  }

  private static void place(int[] vertices, int index, Direction direction, float depth, float u, float v) {
    switch (direction) {
      case UP -> place(vertices, index, u, depth, v);
      case DOWN -> place(vertices, index, u, depth, 1F - v);
      case NORTH -> place(vertices, index, 1F - u, 1F - v, depth);
      case SOUTH -> place(vertices, index, u, 1F - v, depth);
      case WEST -> place(vertices, index, depth, 1F - v, u);
      case EAST -> place(vertices, index, depth, 1F - v, 1F - u);
    }
  }

  private static void place(int[] vertices, int index, float x, float y, float z) {
    int position = index + POSITION;
    vertices[position] = bits(x);
    vertices[position + 1] = bits(y);
    vertices[position + 2] = bits(z);
  }

  private static float read(int[] vertices, int index) {
    return Float.intBitsToFloat(vertices[index]);
  }

  private static int bits(float value) {
    return Float.floatToRawIntBits(value);
  }

  private static boolean near(float first, float second, float epsilon) {
    return Math.abs(first - second) <= epsilon;
  }
}
