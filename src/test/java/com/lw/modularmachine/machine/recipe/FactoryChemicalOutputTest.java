package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FactoryChemicalOutputTest {
  private static final ResourceLocation OXYGEN = ResourceLocation.parse("mekanism:oxygen");
  private static final ResourceLocation HYDROGEN = ResourceLocation.parse("mekanism:hydrogen");

  @Test
  void plans2048RunsWith33OutputRowsAfterMerging() {
    var outputs = Collections.nCopies(33, new ChemicalRequirement(OXYGEN, 100_000));
    var runs = new ArrayList<FrozenItemRun>();
    for (int index = 0; index < 2048; index++) {
      runs.add(pendingRun(index, outputs));
    }
    var claims = FactoryOutputEngine.chemicalOutputClaims(FactoryRunSet.ofRuns(runs));
    long total = 2048L * 33 * 100_000;
    var key = new ChemicalResourceKey(OXYGEN, "");
    assertEquals(List.of(new RecipeCandidate.Output<>(key, total)), claims);

    var snapshot = new ItemResourceSnapshot<ChemicalResourceKey>(
        0,
        Map.of(),
        List.of(new ItemResourceSnapshot.OutputSlot<>(null, 0, total)));
    var result = RecipePlanner.prepareOutputs(snapshot, RecipeCandidate.outputOnly("chemical_parallel", claims))
        .advance(4096);
    assertEquals(RecipePlanner.Status.SELECTED, result.status());
    var insertions = result.plan().orElseThrow().insertions();
    assertEquals(1, insertions.size());
    assertEquals(key, insertions.getFirst().key());
    assertEquals(total, insertions.getFirst().count());
  }

  @Test
  void keepsChemicalAndSelectorSeparateWhenMergingRuns() {
    var outputs = List.of(
        new ChemicalRequirement(OXYGEN, 2, "product"),
        new ChemicalRequirement(OXYGEN, 3, ""),
        new ChemicalRequirement(HYDROGEN, 5, "product"));
    var set = FactoryRunSet.ofRuns(List.of(pendingRun(0, outputs), pendingRun(1, outputs)));

    assertEquals(
        List.of(
            new RecipeCandidate.Output<>(new ChemicalResourceKey(OXYGEN, "product"), 4),
            new RecipeCandidate.Output<>(new ChemicalResourceKey(OXYGEN, ""), 6),
            new RecipeCandidate.Output<>(new ChemicalResourceKey(HYDROGEN, "product"), 10)),
        FactoryOutputEngine.chemicalOutputClaims(set));
  }

  private static FrozenItemRun pendingRun(int index, List<ChemicalRequirement> outputs) {
    var source = FrozenItemRunTest.valid();
    source.putUUID("run_id", new UUID(0, index));
    var definition = source.getCompound("definition");
    definition.put("outputs", new ListTag());
    var chemicals = new CompoundTag();
    var rows = new ListTag();
    for (var output : outputs) {
      var row = new CompoundTag();
      row.putString("chemical", output.chemical().toString());
      row.putLong("amount", output.amount());
      row.putString("selector", output.selector());
      rows.add(row);
    }
    chemicals.put("outputs", rows);
    definition.put("chemicals", chemicals);
    var ports = new ListTag();
    ports.add(source.getList("ports", Tag.TAG_COMPOUND).getCompound(0).copy());
    for (var selector : outputs.stream().map(ChemicalRequirement::selector).distinct().toList()) {
      var port = new CompoundTag();
      port.putString("id", "out_" + ports.size());
      port.putString("kind", "CHEMICAL_OUTPUT");
      port.putString("selector", selector);
      ports.add(port);
    }
    source.put("ports", ports);

    return FrozenItemRun.read(source).advanced(1, 20).finished();
  }
}
