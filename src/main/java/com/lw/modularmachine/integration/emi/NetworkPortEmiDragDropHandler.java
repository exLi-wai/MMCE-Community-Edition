package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.client.NetworkPortScreen;
import com.lw.modularmachine.integration.mekanism.emi.MekanismEmiIngredients;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.NetworkResourceSamples;
import com.lw.modularmachine.util.Mods;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

final class NetworkPortEmiDragDropHandler implements EmiDragDropHandler<NetworkPortScreen> {
  static final NetworkPortEmiDragDropHandler INSTANCE = new NetworkPortEmiDragDropHandler();

  private static final class Drag {
    private final NetworkPortScreen screen;
    private final UUID session;
    private final long revision;
    private EmiIngredient ingredient;
    private List<NetworkPortResource> candidates = List.of();

    private Drag(NetworkPortScreen screen) {
      this.screen = screen;
      var status = screen.getMenu().status();
      session = status.session();
      revision = status.snapshot().configRevision();
    }

    private boolean current(NetworkPortScreen screen) {
      if (this.screen != screen || !screen.canAcceptGhosts()) {
        return false;
      }
      var status = screen.getMenu().status();
      return session.equals(status.session()) && revision == status.snapshot().configRevision();
    }

    private List<NetworkPortResource> candidates(EmiIngredient dragged) {
      if (ingredient != dragged) {
        var resource = screen.getMenu().port().kind().resource();
        var choices = new LinkedHashSet<NetworkPortResource>();
        for (var stack : dragged.getEmiStacks()) {
          if (!stack.isEmpty()) {
            prototype(resource, stack).ifPresent(choices::add);
          }
        }
        candidates = List.copyOf(choices);
        ingredient = dragged;
      }
      return candidates;
    }
  }

  private Drag drag;
  private final BitSet pickerButtons = new BitSet();

  private NetworkPortEmiDragDropHandler() {
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::mouseButton);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::pickerScrolled);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::renderPicker);
    NeoForge.EVENT_BUS.addListener(this::closing);
    NeoForge.EVENT_BUS.addListener(this::initializing);
  }

  private void mouseButton(InputEvent.MouseButton.Pre event) {
    var client = Minecraft.getInstance();
    if (handlePickerButton(client, event)) {
      event.setCanceled(true);
      return;
    }
    if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
      return;
    }
    if (event.getAction() == GLFW.GLFW_PRESS) {
      drag = client.screen instanceof NetworkPortScreen screen && screen.canAcceptGhosts() ? new Drag(screen) : null;
    } else if (event.getAction() == GLFW.GLFW_RELEASE && drag != null) {
      // EMI dispatches drops after this raw input hook and can consume the screen
      // release.
      var released = drag;
      client.tell(() -> {
        if (drag == released) {
          drag = null;
        }
      });
    }
  }

  private boolean handlePickerButton(Minecraft client, InputEvent.MouseButton.Pre event) {
    int button = event.getButton();
    boolean pressed = event.getAction() == GLFW.GLFW_PRESS;
    boolean capturedRelease = !pressed && pickerButtons.get(button);
    pickerButtons.clear(button);
    if (!(client.screen instanceof NetworkPortScreen screen) || !screen.hasGhostPicker()) {
      return capturedRelease;
    }
    var window = client.getWindow();
    double x = client.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
    double y = client.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
    if (pressed) {
      // An outside click or a selection can close the picker before the matching
      // release.
      pickerButtons.set(button);
      screen.mouseClicked(x, y, button);
    } else {
      screen.mouseReleased(x, y, button);
    }
    return true;
  }

  private void pickerScrolled(ScreenEvent.MouseScrolled.Pre event) {
    if (event.getScreen() instanceof NetworkPortScreen screen && screen.hasGhostPicker()) {
      screen.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDeltaX(), event.getScrollDeltaY());
      event.setCanceled(true);
    }
  }

  private void renderPicker(ScreenEvent.Render.Post event) {
    if (event.getScreen() instanceof NetworkPortScreen screen && screen.hasGhostPicker()) {
      screen.renderGhostPicker(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }
  }

  private void closing(ScreenEvent.Closing event) {
    if (drag != null && drag.screen == event.getScreen()) {
      drag = null;
    }
  }

  private void initializing(ScreenEvent.Init.Pre event) {
    if (drag != null && drag.screen == event.getScreen()) {
      drag = null;
    }
  }

  @Override
  public boolean dropStack(NetworkPortScreen screen, EmiIngredient ingredient, int mouseX, int mouseY) {
    var released = drag;
    drag = null;
    int slot = screen.ghostSlotAt(mouseX, mouseY);
    if (slot < 0) {
      return false;
    }
    if (released != null && released.current(screen)) {
      var candidates = released.candidates(ingredient);
      if (!candidates.isEmpty()) {
        screen.chooseGhost(slot, candidates, released.session, released.revision);
      }
    }
    // The JEMI fallback would replace an ambiguous ingredient with its first stack.
    return true;
  }

  @Override
  public void render(NetworkPortScreen screen, EmiIngredient ingredient, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    if (drag == null || !drag.current(screen) || drag.candidates(ingredient).isEmpty()) {
      return;
    }
    var status = screen.getMenu().status();
    for (int slot = 0; slot < status.snapshot().rows().size(); slot++) {
      var area = screen.ghostArea(slot);
      int x = area.getX();
      int y = area.getY();
      graphics.fill(x, y, x + area.getWidth(), y + area.getHeight(), 0x8822BB33);
    }
  }

  private static Optional<NetworkPortResource> prototype(ResourceType resource, EmiStack stack) {
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
