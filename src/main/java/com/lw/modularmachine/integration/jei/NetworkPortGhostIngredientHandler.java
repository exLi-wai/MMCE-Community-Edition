package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.client.NetworkPortScreen;
import com.lw.modularmachine.integration.mekanism.jei.MekanismGhostIngredients;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.util.Mods;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * JEI drops configure prototypes; they never insert real items or network
 * stock.
 */
final class NetworkPortGhostIngredientHandler implements IGhostIngredientHandler<NetworkPortScreen> {
  @Override
  public <I> List<Target<I>> getTargetsTyped(NetworkPortScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
    if (!screen.canAcceptGhosts()
        || convert(screen.getMenu().port().kind().resource(), ingredient.getType(), ingredient.getIngredient())
            .isEmpty()) {
      return List.of();
    }
    var status = screen.getMenu().status();
    if (status == null || !screen.canAcceptGhosts()) {
      return List.of();
    }
    var targets = new ArrayList<Target<I>>(9);
    for (int index = 0; index < 9; index++) {
      int slot = index;
      var area = screen.ghostArea(slot);
      var captured = new Rect2i(area.getX(), area.getY(), area.getWidth(), area.getHeight());
      targets.add(new Target<>() {
        @Override
        public Rect2i getArea() {
          return new Rect2i(captured.getX(), captured.getY(), captured.getWidth(), captured.getHeight());
        }

        @Override
        public void accept(I dropped) {
          if (!screen.canAcceptGhosts() || !sameArea(captured, screen.ghostArea(slot))) {
            return;
          }
          convert(screen.getMenu().port().kind().resource(), ingredient.getType(), dropped).ifPresent(
              resource -> screen.acceptGhost(slot, resource, status.session(), status.snapshot().configRevision()));
        }
      });
    }

    return List.copyOf(targets);
  }

  private static boolean sameArea(Rect2i expected, Rect2i actual) {
    return expected.getX() == actual.getX()
        && expected.getY() == actual.getY()
        && expected.getWidth() == actual.getWidth()
        && expected.getHeight() == actual.getHeight();
  }

  private static <I> Optional<NetworkPortResource> convert(PortKind.Resource resource, IIngredientType<I> type, I ingredient) {
    if (type.equals(VanillaTypes.ITEM_STACK) && ingredient instanceof ItemStack item && !item.isEmpty()) {
      var sample = item.copyWithCount(1);
      return switch (resource) {
        case ITEM -> Optional.of(new NetworkPortResource.Item(sample));
        case FLUID ->
          FluidUtil.getFluidContained(sample).filter(fluid -> !fluid.isEmpty()).map(NetworkPortResource.Fluid::new);
        case CHEMICAL -> {
          var chemical = ChemicalPlatform.sampleContainer(sample);
          yield chemical.isEmpty() ? Optional.empty() : Optional
              .of(new NetworkPortResource.Chemical(chemical.chemical()));
        }
        case ENERGY -> Optional.empty();
      };
    }
    if (resource == PortKind.Resource.FLUID
        && type.equals(NeoForgeTypes.FLUID_STACK)
        && ingredient instanceof FluidStack fluid
        && !fluid.isEmpty()) {
      return Optional.of(new NetworkPortResource.Fluid(fluid));
    }
    if (resource == PortKind.Resource.CHEMICAL && Mods.MEKANISM.isLoaded()) {
      return MekanismGhostIngredients.convert(type, ingredient);
    }
    return Optional.empty();
  }

  @Override
  public void onComplete() {
  }
}
