package com.lw.modularmachine.machine.assembly;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialAllocationTest {
  @Test
  void failedCandidateCanUseAnUnassignedAlternative() {
    var replacement = MaterialAllocation
        .reassign(new long[]{1, 1}, List.of(new MaterialAllocation.Demand(1, List.of(0, 1))), 0, Set.of(0));
    assertNotNull(replacement);
    assertEquals(1, replacement.resource());
    assertEquals(List.of(Map.of(1, 1L)), replacement.assignments());
  }

  @Test
  void retryReroutesAnotherGroupWithoutLosingItsReservation() {
    var replacement = MaterialAllocation.reassign(
        new long[]{1, 1, 1},
        List.of(
            new MaterialAllocation.Demand(1, List.of(0, 1)),
            new MaterialAllocation.Demand(1, List.of(1, 2)),
            new MaterialAllocation.Demand(1, List.of(0))),
        0,
        Set.of(0));
    assertNotNull(replacement);
    assertEquals(1, replacement.resource());
    assertEquals(List.of(Map.of(1, 1L), Map.of(2, 1L), Map.of(0, 1L)), replacement.assignments());
  }

  @Test
  void retryCannotStealTheOnlyMaterialReservedForAnotherGroup() {
    assertNull(
        MaterialAllocation.reassign(
            new long[]{1, 1},
            List.of(new MaterialAllocation.Demand(1, List.of(0, 1)), new MaterialAllocation.Demand(1, List.of(1))),
            0,
            Set.of(0)));
  }

  @Test
  void rejectedCandidateIsExcludedOnlyForTheCurrentPosition() {
    var replacement = MaterialAllocation
        .reassign(new long[]{2, 1}, List.of(new MaterialAllocation.Demand(3, List.of(0, 1))), 0, Set.of(0));
    assertNotNull(replacement);
    assertEquals(Map.of(0, 2L, 1, 1L), replacement.assignments().getFirst());
    assertNull(
        MaterialAllocation
            .reassign(new long[]{2, 1}, List.of(new MaterialAllocation.Demand(3, List.of(0, 1))), 0, Set.of(0, 1)));
  }

  @Test
  void overlappingAlternativesLeaveTheExclusiveMaterialAvailable() {
    var demands = List
        .of(new MaterialAllocation.Demand(1, List.of(0, 1)), new MaterialAllocation.Demand(1, List.of(0)));
    var result = MaterialAllocation.allocate(new long[]{1, 1}, demands);
    assertEquals(Map.of(1, 1L), result.get(0));
    assertEquals(Map.of(0, 1L), result.get(1));
  }

  @Test
  void onePhysicalPoolCannotBeCountedForBothGroupsAndAmountsRemainLong() {
    var demands = List
        .of(new MaterialAllocation.Demand(3_000_000_000L, List.of(0)), new MaterialAllocation.Demand(3, List.of(0)));
    var result = MaterialAllocation.allocate(new long[]{3_000_000_001L}, demands);
    assertEquals(
        3_000_000_001L,
        result.stream().flatMap(group -> group.values().stream()).mapToLong(Long::longValue).sum());
    assertEquals(1L, result.get(1).get(0));
  }

  @Test
  void partialBucketReducesTheDeficitWithoutMixingComponentIdentities() {
    var result = MaterialAllocation
        .plan(new long[]{600, 400}, new int[]{1000, 1000}, List.of(new MaterialAllocation.Demand(1, List.of(0, 1))));
    assertTrue(result.full().getFirst().isEmpty());
    assertEquals(Map.of(0, 1L), result.partial().getFirst());
    assertEquals(400, 1000 - new long[]{600, 400}[result.partial().getFirst().keySet().iterator().next()]);
    assertArrayEquals(new long[]{0}, result.remaining());
  }

  @Test
  void aPartialAlternativeReroutesAFullBucketToTheExclusiveGroup() {
    var result = MaterialAllocation.plan(
        new long[]{1000, 600},
        new int[]{1000, 1000},
        List.of(new MaterialAllocation.Demand(1, List.of(0, 1)), new MaterialAllocation.Demand(1, List.of(0))));
    assertEquals(Map.of(0, 1L), result.full().get(1));
    assertEquals(Map.of(1, 1L), result.partial().get(0));
    assertArrayEquals(new long[]{0, 0}, result.remaining());
  }

  @Test
  void fullBucketsAndRemnantsAreEachAssignedOnceAcrossAlternatives() {
    var result = MaterialAllocation.plan(
        new long[]{1600, 400},
        new int[]{1000, 1000},
        List.of(new MaterialAllocation.Demand(2, List.of(0, 1)), new MaterialAllocation.Demand(1, List.of(0))));
    assertEquals(1, result.full().stream().flatMap(group -> group.values().stream()).mapToLong(Long::longValue).sum());
    assertEquals(
        2,
        result.partial().stream().flatMap(group -> group.values().stream()).mapToLong(Long::longValue).sum());
    assertArrayEquals(new long[]{0, 0}, result.remaining());
  }
}
