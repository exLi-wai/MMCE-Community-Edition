package com.lw.modularmachine.command;

import com.lw.modularmachine.machine.structure.StructureService;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * Carries the selected packs and definition result through one vanilla reload.
 */
@ApiStatus.Internal
public final class MachineReloadRequest extends AbstractCollection<String> {
  private final List<String> selectedPacks;
  private Optional<String> definitionRejection = Optional.empty();

  MachineReloadRequest(Collection<String> selectedPacks) {
    this.selectedPacks = List.copyOf(selectedPacks);
  }

  @Override
  public Iterator<String> iterator() {
    return selectedPacks.iterator();
  }

  @Override
  public int size() {
    return selectedPacks.size();
  }

  public void recordDefinitionResult(RecipeManager manager) {
    definitionRejection = StructureService.definitionReloadRejection(manager);
  }

  Optional<String> definitionRejection() {
    return definitionRejection;
  }
}
