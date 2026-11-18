package com.lw.modularmachine.machine.recipe.resource.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData.Claim;
import com.lw.modularmachine.machine.recipe.resource.world.LocalAuraAllocation.Node;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LocalAuraAllocationTest {
  @Test
  void mergesIdenticalFactoryRunClaimsBeforeAllocating() {
    var corner = new Node(new BlockPos(16, 64, 16), 10, 10, Long.MAX_VALUE);
    var west = new Node(new BlockPos(15, 64, 16), 10, 10, Long.MAX_VALUE);
    var north = new Node(new BlockPos(16, 64, 15), 10, 10, Long.MAX_VALUE);
    var nodes = List.of(corner, west, north);
    var run = new Claim("aura", 10, false, 0, 10);
    assertArrayEquals(new long[]{10, 0, 0}, LocalAuraAllocation.allocate(nodes, run.amount()));

    var batch = new WorldPoolExecution.ClaimBatch(true);
    assertTrue(batch.add(run));
    assertTrue(batch.add(run));
    var claims = batch.iterator();
    assertArrayEquals(new long[]{0, 10, 10}, LocalAuraAllocation.allocate(nodes, claims.next().amount()));
    assertFalse(claims.hasNext());
  }

  @Test
  void differentLimitsRemainSeparateAndClaimTotalsCannotWrap() {
    var batch = new WorldPoolExecution.ClaimBatch(true);
    var relaxed = new Claim("aura", Long.MAX_VALUE, false, 0, 20);
    var strict = new Claim("aura", 10, false, 0, 10);
    assertTrue(batch.add(relaxed));
    assertTrue(batch.add(strict));
    assertFalse(batch.add(relaxed));
    var claims = batch.iterator();
    assertEquals(strict, claims.next());
    assertEquals(relaxed, claims.next());
    assertFalse(claims.hasNext());
  }

  @Test
  void cornerCannotConsumeBothEndSpotsHeadroom() {
    var corner = new Node(new BlockPos(16, 64, 16), 10, 10, Long.MAX_VALUE);
    var west = new Node(new BlockPos(15, 64, 16), 10, 10, Long.MAX_VALUE);
    var north = new Node(new BlockPos(16, 64, 15), 10, 10, Long.MAX_VALUE);

    assertArrayEquals(new long[]{0, 10, 10}, LocalAuraAllocation.allocate(List.of(corner, west, north), 20));
    assertArrayEquals(new long[]{10, 0, 10}, LocalAuraAllocation.allocate(List.of(north, corner, west), 20));
    assertArrayEquals(new long[]{10, 10, 0}, LocalAuraAllocation.allocate(List.of(west, north, corner), 21));
  }

  @Test
  void fourChunkCornerPreservesEarlierClaimsAndUsesRemainingHeadroom() {
    var positions = List
        .of(new BlockPos(15, 64, 15), new BlockPos(16, 64, 15), new BlockPos(15, 64, 16), new BlockPos(16, 64, 16));
    for (int seed = 0; seed < 32; seed++) {
      var nodes = List.of(
          new Node(positions.get(0), 3, seed % 5, seed % 4),
          new Node(positions.get(1), 2, (seed + 1) % 7, Long.MAX_VALUE),
          new Node(positions.get(2), 1, (seed + 2) % 4, Long.MAX_VALUE),
          new Node(positions.get(3), 3, (seed + 3) % 6, Long.MAX_VALUE));
      for (int requested = 1; requested <= 8; requested++) {
        long expected = maximum(nodes, requested);
        assertEquals(expected, total(LocalAuraAllocation.allocate(nodes, requested)));
        assertEquals(expected, total(LocalAuraAllocation.allocate(nodes.reversed(), requested)));
      }
    }
  }

  private static long maximum(List<Node> nodes, int requested) {
    long best = 0;
    for (int first = 0; first <= 3; first++) {
      for (int second = 0; second <= 2; second++) {
        for (int third = 0; third <= 1; third++) {
          for (int fourth = 0; fourth <= 3; fourth++) {
            var amounts = new long[]{first, second, third, fourth};
            long sum = total(amounts);
            if (sum <= requested && fits(nodes, amounts)) {
              best = Math.max(best, sum);
            }
          }
        }
      }
    }
    return best;
  }

  private static boolean fits(List<Node> nodes, long[] amounts) {
    for (int index = 0; index < nodes.size(); index++) {
      var node = nodes.get(index);
      long nearby = 0;
      for (int other = 0; other < nodes.size(); other++) {
        if (node.position().distManhattan(nodes.get(other).position()) <= 1) {
          nearby += amounts[other];
        }
      }
      if (nearby > node.reservedSpace() || amounts[index] > 0 && nearby > node.claimSpace()) {
        return false;
      }
    }
    return true;
  }

  private static long total(long[] amounts) {
    long result = 0;
    for (long amount : amounts) {
      result += amount;
    }
    return result;
  }
}
