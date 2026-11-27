package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.ApiStatus;

/**
 * Posted on the NeoForge game event bus once per server recipe reload,
 * including initial load. Add declarations synchronously. IDs are shared with
 * data packs, scripts and adapters and must be unique.
 */
public final class RegisterMachineRecipesEvent extends Event {
  private final HolderLookup.Provider registries;
  private final Thread owner = Thread.currentThread();
  private final Map<ResourceLocation, MachineRecipeDeclaration> declarations = new LinkedHashMap<>();
  private boolean open = true;

  private RegisterMachineRecipesEvent(HolderLookup.Provider registries) {
    this.registries = registries;
  }

  public HolderLookup.Provider registries() {
    return registries;
  }

  public void add(ResourceLocation id, MachineRecipeDeclaration declaration) {
    requireOpen();
    put(id, MachineRecipeCompiler.capture(declaration, registries));
  }

  public void add(MachineRecipeBuilder builder) {
    requireOpen();
    put(builder.id(), builder.build(registries));
  }

  private void requireOpen() {
    if (Thread.currentThread() != owner || !open) {
      throw new IllegalStateException("Machine recipes can only be added during the registration event");
    }
  }

  private void put(ResourceLocation id, MachineRecipeDeclaration declaration) {
    if (declarations.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(declaration)) != null) {
      throw new IllegalArgumentException("Duplicate Java machine recipe ID: " + id);
    }
  }

  @ApiStatus.Internal
  public static Map<ResourceLocation, MachineRecipeDeclaration> collect(HolderLookup.Provider registries, Consumer<RegisterMachineRecipesEvent> dispatch) {
    var event = new RegisterMachineRecipesEvent(registries);
    try {
      dispatch.accept(event);
      return Map.copyOf(event.declarations);
    } finally {
      event.open = false;
      event.declarations.clear();
    }
  }
}
