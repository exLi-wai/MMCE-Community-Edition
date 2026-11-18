package com.lw.modularmachine.machine.recipe.resource.pneumatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.lw.modularmachine.api.recipe.CompressedAirRecipeData.Amount;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompressedAirRecipeExecutionTest {
  @Test
  void equalPressureRunsPayTheSameDebitAsOneScaledAdmissionClaim() {
    var hatch = new Port(20);
    var ports = List.<CompressedAirPort>of(hatch);
    assertNotNull(CompressedAirRecipeExecution.inputs(ports, List.of(new Amount(2, 16))));
    var payment = CompressedAirRecipeExecution.inputs(ports, List.of(new Amount(2, 8), new Amount(2, 8)));
    assertNotNull(payment);
    payment.acquire();
    assertEquals(4, hatch.air());
    payment.resolve(false);
    assertEquals(20, hatch.air());
  }

  @Test
  void lowerPressureClaimsCanUseStockLeftByTheHigherPressureGroup() {
    var hatch = new Port(20);
    var ports = List.<CompressedAirPort>of(hatch);
    var payment = CompressedAirRecipeExecution.inputs(ports, List.of(new Amount(1, 12), new Amount(2, 8)));
    assertNotNull(payment);
    assertNull(CompressedAirRecipeExecution.inputs(ports, List.of(new Amount(1.5F, 12), new Amount(2, 8))));
    payment.acquire();
    payment.resolve(true);
    assertEquals(0, hatch.air());
  }

  private static final class Port implements CompressedAirPort {
    private int air;

    private Port(int air) {
      this.air = air;
    }

    @Override
    public int air() {
      return air;
    }

    @Override
    public int volume() {
      return 10;
    }

    @Override
    public int outputSpace() {
      return 0;
    }

    @Override
    public RecipeResourceParticipant acquireAirChange(int expected, int delta) {
      assertEquals(expected, air);
      air += delta;
      return commit -> {
        if (!commit) {
          air -= delta;
        }
      };
    }

    @Override
    public void publishAirChange() {
    }
  }
}
