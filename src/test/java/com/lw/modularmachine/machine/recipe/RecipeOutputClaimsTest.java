package com.lw.modularmachine.machine.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.ResourceSnapshot;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecipeOutputClaimsTest {
  @Test
  void ignoredRowsKeepTheirOrderAcrossDifferentResourceKeys() {
    var claims = new RecipeOutputClaims<String>();
    claims.add("a", 1, true);
    claims.add("b", 1, true);
    claims.add("a", 100, true);
    var snapshot = new ResourceSnapshot<String>(
        0,
        Map.of(),
        List.of(new ResourceSnapshot.OutputSlot<>(null, 0, 100), new ResourceSnapshot.OutputSlot<>(null, 0, 100)));
    var outputs = RecipeCandidate.outputOnly("ordered", claims.claims());
    var plan = RecipePlanner.prepareOutputs(snapshot, outputs).advance(100).plan().orElseThrow();

    assertEquals(List.of("a", "b"), plan.insertions().stream().map(insertion -> insertion.key()).toList());
    assertEquals(List.of(100L, 1L), plan.insertions().stream().map(insertion -> insertion.count()).toList());
  }

  @Test
  void ignoredIndependentRunsKeepEveryLongSizedSegment() {
    var claims = new RecipeOutputClaims<String>();
    claims.add("chemical", Long.MAX_VALUE, true);
    claims.add("chemical", Long.MAX_VALUE, true);
    claims.add("chemical", 7, true);

    assertEquals(3, claims.claims().size());
    assertEquals(
        BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.valueOf(7)),
        claims.claims().stream().map(claim -> BigInteger.valueOf(claim.count()))
            .reduce(BigInteger.ZERO, BigInteger::add));
  }

  @Test
  void requiredAmountsStillRejectOverflow() {
    var claims = new RecipeOutputClaims<String>();
    claims.add("item", Long.MAX_VALUE, false);

    assertThrows(ArithmeticException.class, () -> claims.add("item", 1, false));
  }

  @Test
  void requiredAndIgnoredAmountsRemainSeparateForTheSameKey() {
    var claims = new RecipeOutputClaims<String>();
    claims.add("item", 9, false);
    claims.add("item", Long.MAX_VALUE, true);
    var result = claims.claims();

    assertEquals(
        9,
        result.stream().filter(claim -> !claim.ignoreOutputCheck()).mapToLong(claim -> claim.count()).sum());
    assertEquals(
        Long.MAX_VALUE,
        result.stream().filter(claim -> claim.ignoreOutputCheck()).mapToLong(claim -> claim.count()).sum());
  }
}
