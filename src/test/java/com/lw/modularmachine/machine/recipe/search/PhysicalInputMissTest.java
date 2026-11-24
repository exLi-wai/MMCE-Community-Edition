package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.BitSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalInputMissTest {
  @Test
  void chemicalOnlyMissDoesNotEnumerateRanksOrProvePhysicalShortage() {
    var noItems = new Probe(false, -1, true);
    assertFalse(RecipeCatalog.physicalInputsExcluded(noItems, null, true, -1));
    assertFalse(RecipeCatalog.physicalInputsExcluded(noItems, new Probe(false, -1, true), true, 50_000));
  }

  @Test
  void mixedCatalogKeepsBroadRetriesAndChecksOnlyTheLockedRank() {
    var items = new Probe(true, 40_000, true);
    var missingFluid = new Probe(true, 40_000, false);
    assertFalse(RecipeCatalog.physicalInputsExcluded(items, missingFluid, true, -1));
    assertTrue(RecipeCatalog.physicalInputsExcluded(items, missingFluid, true, 40_000));
    assertFalse(RecipeCatalog.physicalInputsExcluded(items, null, true, 40_000));
    assertTrue(
        RecipeCatalog.physicalInputsExcluded(new Probe(true, 40_000, false), new Probe(true, -1, true), true, 40_000));
  }

  @Test
  void emptyIntersectionWithOnlyPhysicalAnchorsNeedsNoSecondQuery() {
    var items = new Probe(true, -1, false);
    var fluids = new Probe(true, -1, false);
    assertTrue(RecipeCatalog.physicalInputsExcluded(items, fluids, false, -1));
    assertTrue(RecipeCatalog.physicalInputsExcluded(new Probe(false, -1, true), fluids, false, -1));
  }

  private record Probe(boolean hasAnchors, int expectedRank, boolean matches) implements ResourceInputQuery {
    @Override
    public boolean matchesRank(int rank) {
      assertEquals(expectedRank, rank, "Unexpected rank probe");
      return matches;
    }

    @Override
    public int candidateCount() {
      throw new AssertionError("Unexpected candidate enumeration");
    }

    @Override
    public BitSet select(Set<String> allowedIds) {
      throw new AssertionError("Unexpected rank bitmap allocation");
    }

    @Override
    public void retainMatches(BitSet selected) {
      throw new AssertionError("Unexpected rank scan");
    }
  }
}
