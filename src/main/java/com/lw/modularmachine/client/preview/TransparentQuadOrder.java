package com.lw.modularmachine.client.preview;

import it.unimi.dsi.fastutil.ints.IntArrays;
import java.util.List;

/** Pure global quad ordering across mesh pages and sections. */
final class TransparentQuadOrder {
  record Source(float[] centers, double x, double y, double z) {
  }
  record Result(int[] indices) {
  }

  private TransparentQuadOrder() {
  }

  static float[] centers(int[] vertices) {
    var result = new float[vertices.length / 8];
    for (int quad = 0; quad < vertices.length / 24; quad++) {
      for (int axis = 0; axis < 3; axis++) {
        float sum = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
          sum += Float.intBitsToFloat(vertices[quad * 24 + vertex * 6 + axis]);
        }
        result[quad * 3 + axis] = sum * .25F;
      }
    }
    return result;
  }

  static Result sort(List<Source> sources, double x, double y, double z) {
    int count = sources.stream().mapToInt(source -> source.centers().length / 3).reduce(0, Math::addExact);
    var order = new int[count];
    var distance = new double[count];
    int cursor = 0;
    for (int page = 0; page < sources.size(); page++) {
      var source = sources.get(page);
      int size = source.centers().length / 3;
      for (int quad = 0; quad < size; quad++) {
        double dx = source.x() + source.centers()[quad * 3] - x;
        double dy = source.y() + source.centers()[quad * 3 + 1] - y;
        double dz = source.z() + source.centers()[quad * 3 + 2] - z;
        order[cursor] = cursor;
        distance[cursor++] = dx * dx + dy * dy + dz * dz;
      }
    }
    IntArrays.quickSort(order, (left, right) -> {
      int comparison = Double.compare(distance[right], distance[left]);
      return comparison == 0 ? Integer.compare(left, right) : comparison;
    });
    var indices = new int[Math.multiplyExact(count, 6)];
    for (int index = 0; index < order.length; index++) {
      int vertex = Math.multiplyExact(order[index], 4), offset = index * 6;
      indices[offset] = vertex;
      indices[offset + 1] = vertex + 1;
      indices[offset + 2] = vertex + 2;
      indices[offset + 3] = vertex + 2;
      indices[offset + 4] = vertex + 3;
      indices[offset + 5] = vertex;
    }
    return new Result(indices);
  }

  /** Rebase a source page to one shared origin before merging vertex buffers. */
  static byte[] relocate(int[] vertices, double x, double y, double z) {
    var copy = vertices.clone();
    for (int vertex = 0; vertex < copy.length; vertex += 6) {
      copy[vertex] = Float.floatToRawIntBits((float) (Float.intBitsToFloat(copy[vertex]) + x));
      copy[vertex + 1] = Float.floatToRawIntBits((float) (Float.intBitsToFloat(copy[vertex + 1]) + y));
      copy[vertex + 2] = Float.floatToRawIntBits((float) (Float.intBitsToFloat(copy[vertex + 2]) + z));
    }
    return new PreviewMeshData.Page(copy, true).uploadBytes();
  }
}
