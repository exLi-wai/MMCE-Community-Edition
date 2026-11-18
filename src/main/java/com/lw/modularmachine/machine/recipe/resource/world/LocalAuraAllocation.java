package com.lw.modularmachine.machine.recipe.resource.world;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Exact within one component of adjacent output spots in distinct chunks. */
final class LocalAuraAllocation {
  record Node(BlockPos position, long capacity, long claimSpace, long reservedSpace) {
  }

  private LocalAuraAllocation() {
  }

  static long[] allocate(List<Node> nodes, long requested) {
    int size = nodes.size();
    if (size == 1) {
      var node = nodes.getFirst();
      return new long[]{Math
          .max(0, Math.min(requested, Math.min(node.capacity(), Math.min(node.claimSpace(), node.reservedSpace()))))};
    }
    if (size > 4) {
      throw new IllegalStateException("A radius-one aura component cannot span more than four chunks");
    }
    var best = new long[size];
    var lower = new long[size];
    var excluded = new int[size];
    var limits = new long[size];
    Arrays.fill(excluded, -1);
    for (int index = 0; index < size; index++) {
      for (int other = 0; other < size; other++) {
        if (nodes.get(index).position().distManhattan(nodes.get(other).position()) > 1) {
          excluded[index] = other;
          break;
        }
      }
    }
    long allocated = 0;
    for (int mask = 1; mask < 1 << size; mask++) {
      long upper = 0;
      for (int index = 0; index < size; index++) {
        var node = nodes.get(index);
        boolean active = selected(mask, index);
        upper += active ? Math.max(0, node.capacity()) : 0;
        limits[index] = active ? Math.min(node.claimSpace(), node.reservedSpace()) : node.reservedSpace();
      }
      long low = Math.max(allocated + 1, Integer.bitCount(mask));
      long high = Math.min(requested, upper);
      if (low > high || !fits(nodes, mask, low, excluded, limits, lower)) {
        continue;
      }
      while (low < high) {
        long middle = low + (high - low + 1) / 2;
        if (fits(nodes, mask, middle, excluded, limits, lower)) {
          low = middle;
        } else {
          high = middle - 1;
        }
      }
      fits(nodes, mask, low, excluded, limits, best);
      long unassigned = low - Arrays.stream(best).sum();
      for (int index = 0; index < size && unassigned > 0; index++) {
        if (selected(mask, index)) {
          long extra = Math.min(unassigned, nodes.get(index).capacity() - best[index]);
          best[index] += extra;
          unassigned -= extra;
        }
      }
      allocated = low;
      if (allocated == requested) {
        break;
      }
    }
    return best;
  }

  private static boolean fits(List<Node> nodes, int mask, long total, int[] excluded, long[] limits, long[] lower) {
    for (int index = 0; index < nodes.size(); index++) {
      lower[index] = selected(mask, index) ? 1 : 0;
    }
    for (int index = 0; index < nodes.size(); index++) {
      if (total <= limits[index]) {
        continue;
      }
      int other = excluded[index];
      if (other < 0 || !selected(mask, other)) {
        return false;
      }
      lower[other] = Math.max(lower[other], total - limits[index]);
    }
    long required = 0;
    for (int index = 0; index < nodes.size(); index++) {
      if (lower[index] > Math.max(0, nodes.get(index).capacity())) {
        return false;
      }
      required += lower[index];
    }
    return required <= total;
  }

  private static boolean selected(int mask, int index) {
    return (mask & 1 << index) != 0;
  }
}
