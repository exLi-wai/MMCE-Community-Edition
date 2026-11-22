package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.client.NetworkPortScreen;
import com.lw.modularmachine.integration.mekanism.emi.MekanismEmiIngredients;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.NetworkResourceSamples;
import com.lw.modularmachine.network.NetworkPortPayloads;
import com.lw.modularmachine.util.Mods;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

final class NetworkPortEmiDragDropHandler implements EmiDragDropHandler<NetworkPortScreen> {
  static final NetworkPortEmiDragDropHandler INSTANCE = new NetworkPortEmiDragDropHandler();

  private record DragTarget(UUID session, long revision) {
    DragTarget(NetworkPortPayloads.Status status) {
      this(status.session(), status.snapshot().configRevision());
    }

    boolean matches(NetworkPortPayloads.Status status) {
      return session.equals(status.session()) && revision == status.snapshot().configRevision();
    }
  }

  private DragTarget pressedTarget;
  private DragTarget visibleTarget;

  private NetworkPortEmiDragDropHandler() {
    NeoForge.EVENT_BUS.addListener(this::mouseButton);
  }

  private void mouseButton(InputEvent.MouseButton.Pre event) {
    if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.getAction() == GLFW.GLFW_PRESS) {
      visibleTarget = null;
      // A fast drop can precede the first render.
      pressedTarget = null;
      if (Minecraft.getInstance().screen instanceof NetworkPortScreen screen && screen.canAcceptGhosts()) {
        pressedTarget = new DragTarget(screen.getMenu().status());
      }
    }
  }

  @Override
  public boolean dropStack(NetworkPortScreen screen, EmiIngredient ingredient, int mouseX, int mouseY) {
    var target = visibleTarget != null ? visibleTarget : pressedTarget;
    pressedTarget = null;
    visibleTarget = null;
    int slot = screen.ghostSlotAt(mouseX, mouseY);
    if (slot < 0 || !screen.canAcceptGhosts()) {
      return false;
    }
    if (target != null && target.matches(screen.getMenu().status())) {
      prototype(screen, ingredient)
          .ifPresent(resource -> screen.acceptGhost(slot, resource, target.session(), target.revision()));
    }
    // The JEMI fallback would replace an ambiguous ingredient with its first stack.
    return true;
  }

  @Override
  public void render(NetworkPortScreen screen, EmiIngredient ingredient, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    if (!screen.canAcceptGhosts() || prototype(screen, ingredient).isEmpty()) {
      return;
    }
    var status = screen.getMenu().status();
    if (pressedTarget == null || !pressedTarget.session().equals(status.session())) {
      return;
    }
    if (visibleTarget == null) {
      visibleTarget = new DragTarget(status);
    }
    if (!visibleTarget.matches(status)) {
      return;
    }
    for (int slot = 0; slot < status.snapshot().rows().size(); slot++) {
      var area = screen.ghostArea(slot);
      int x = area.getX();
      int y = area.getY();
      graphics.fill(x, y, x + area.getWidth(), y + area.getHeight(), 0x8822BB33);
    }
  }

  private static Optional<NetworkPortResource> prototype(NetworkPortScreen screen, EmiIngredient ingredient) {
    var stacks = ingredient.getEmiStacks();
    if (stacks.size() != 1 || stacks.getFirst().isEmpty()) {
      return Optional.empty();
    }
    var stack = stacks.getFirst();
    var resource = screen.getMenu().port().kind().resource();
    if (stack.getKey() instanceof Item item) {
      var sample = new ItemStack(item.builtInRegistryHolder(), 1, stack.getComponentChanges());
      return NetworkResourceSamples.sample(resource, sample);
    }
    if (resource == ResourceType.FLUID && stack.getKey() instanceof Fluid fluid) {
      var sample = new FluidStack(fluid.builtInRegistryHolder(), 1, stack.getComponentChanges());
      return Optional.of(new NetworkPortResource.Fluid(sample));
    }
    if (resource == ResourceType.CHEMICAL && Mods.MEKANISM.isLoaded()) {
      return MekanismEmiIngredients.chemical(stack);
    }
    return Optional.empty();
  }
}
