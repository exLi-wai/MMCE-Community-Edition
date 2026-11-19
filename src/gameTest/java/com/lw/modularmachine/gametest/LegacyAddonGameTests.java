package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.api.recipe.EnvironmentRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRequirement;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.EmberInputHatchBlockEntity;
import com.lw.modularmachine.blockentity.LaserInputHatchBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.EmberHatchTier;
import com.lw.modularmachine.machine.recipe.resource.environment.EnvironmentRecipeExecution;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.lw.modularmachine.integration.botania.SparkManaHatchBlockEntity;
import com.lw.modularmachine.integration.mekanism.HeatHatchBlockEntity;
import com.lw.modularmachine.machine.recipe.resource.pneumatic.CompressedAirPort;
import java.util.function.Supplier;
import me.desht.pneumaticcraft.api.PNCCapabilities;
import net.minecraft.core.Direction;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class LegacyAddonGameTests {
  private static final int COLOR = 0x38A6D2;

  private LegacyAddonGameTests() {
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void environment_hatch_revalidates_position_and_keeps_color(GameTestHelper helper) {
    var level = helper.getLevel();
    var fixture = coloredStructure(helper, "test_environment_color", MachineRegistries.ENVIRONMENT_HATCH.get());
    var ticket = RecipeExecutionTicket.captureComponents(fixture.controller()).orElseThrow();
    var positions = EnvironmentRecipeExecution.positions(ticket);
    var required = new EnvironmentRecipeData(
        List.of(
            new EnvironmentRequirement.Dimension(List.of(level.dimension().location())),
            new EnvironmentRequirement.Altitude(
                fixture.hatch().getBlockPos().getY(),
                fixture.hatch().getBlockPos().getY())));
    helper.assertTrue(
        positions.equals(List.of(fixture.hatch().getBlockPos())),
        "Only installed environment hatches count");
    helper.assertTrue(EnvironmentRecipeExecution.matches(level, positions, required), "Expected environment matches");
    helper.assertTrue(
        !EnvironmentRecipeExecution.matches(level, List.of(), required),
        "No hatch cannot satisfy environment");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void laser_hatch_rolls_back_and_persists_color_and_stock(GameTestHelper helper) {
    if (!Mods.MEKANISM.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var fixture = coloredStructure(helper, "test_laser_color", MachineRegistries.LASER_INPUT_HATCH.get());
    var laser = (LaserInputHatchBlockEntity) fixture.hatch();
    long capacity = laser.capacity();
    long debited = Math.min(34, capacity);
    laser.receive(Long.MAX_VALUE, false);
    try (var lease = laser.acquireRecipeBuffer()) {
      helper.assertTrue(lease.extractExact(debited), "Expected enough laser energy to reserve");
    }
    helper.assertTrue(laser.available() == capacity, "Rollback did not restore the buffer");
    try (var lease = laser.acquireRecipeBuffer()) {
      helper.assertTrue(lease.extractExact(debited), "Expected enough laser energy to debit");
      lease.resolve(true);
    }
    long saved = laser.saveWithoutMetadata(level.registryAccess()).getLong("laser_energy");
    helper.assertTrue(saved == capacity - debited, "Save lost or duplicated laser energy");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void all_ember_tiers_keep_color_and_exact_stock(GameTestHelper helper) {
    if (Mods.EMBERS.isLoaded()) {
      for (var tier : EmberHatchTier.values()) {
        var block = MachineRegistries.EMBER_INPUT_HATCHES.get("ember_input_hatch_" + tier.path()).get();
        var fixture = coloredStructure(helper, "test_ember_" + tier.path() + "_color", block);
        var hatch = (EmberInputHatchBlockEntity) fixture.hatch();
        helper.assertTrue(
            hatch.receive(345_679, true) == 345_679 && hatch.stored() == 0,
            "Simulation changed Ember stock");
        helper.assertTrue(hatch.receive(345_679, false) == 345_679, "Ember input rejected an exact amount");
        try (var lease = hatch.acquireRecipeBuffer()) {
          helper.assertTrue(lease.extractExact(123_456), "Ember debit was unavailable");
          lease.resolve(true);
        }
        helper.assertTrue(hatch.stored() == 222_223, "Ember debit did not preserve exact stock");
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void spark_and_natural_aura_hatches_keep_color(GameTestHelper helper) {
    if (Mods.BOTANIA.isLoaded()) {
      coloredStructure(helper, "test_spark_mana_input_color", MachineRegistries.SPARK_MANA_INPUT.get());
      var fixture = coloredStructure(helper, "test_spark_mana_output_color", MachineRegistries.SPARK_MANA_OUTPUT.get());
      var output = (SparkManaHatchBlockEntity) fixture.hatch();
      long reserved = Math.min(1_000, output.capacity());
      long produced = Math.min(400, output.capacity());
      try (var lease = output.acquireRecipeBuffer()) {
        helper.assertTrue(lease.insertExact(reserved), "Mana output had no reservation space");
      }
      helper.assertTrue(output.stored() == 0, "Mana output rollback left uncommitted production");
      try (var lease = output.acquireRecipeBuffer()) {
        helper.assertTrue(lease.insertExact(produced), "Mana output could not receive production");
        lease.resolve(true);
      }
      helper.assertTrue(output.getCurrentMana() == produced, "Mana output did not commit production");
    }
    if (Mods.NATURES_AURA.isLoaded()) {
      coloredStructure(helper, "test_aura_input_color", MachineRegistries.AURA_INPUT.get());
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void heat_hatches_keep_color_and_conduct_to_input(GameTestHelper helper) {
    if (Mods.MEKANISM.isLoaded()) {
      coloredStructure(helper, "test_heat_input_color", MachineRegistries.HEAT_INPUT.get());
      var fixture = coloredStructure(helper, "test_heat_output_color", MachineRegistries.HEAT_OUTPUT.get());
      var output = (HeatHatchBlockEntity) fixture.hatch();
      var level = helper.getLevel();
      var inputPos = output.getBlockPos().above();
      level.setBlockAndUpdate(inputPos, MachineRegistries.HEAT_INPUT.get().defaultBlockState());
      var input = (HeatHatchBlockEntity) level.getBlockEntity(inputPos);
      long produced = Math.min(10_000, output.capacity());
      try (var lease = output.acquireRecipeBuffer()) {
        helper.assertTrue(lease.insertExact(produced), "Heat output rejected production");
        lease.resolve(true);
      }
      helper.assertTrue(output.stored() == produced, "Heat output did not commit production");
      double difference = output.getTemperature(0) - input.getTotalTemperature();
      double inverseConduction = output.getInverseConduction(0) + input.getTotalInverseConduction();
      long offered = difference > 0 ? Math.max(1, (long) (difference / inverseConduction * 1_000)) : 0;
      long conducted = Math.min(offered, Math.min(produced, input.capacity()));
      output.tickHatch();
      helper.assertTrue(input.stored() == conducted, "Heat input did not receive the expected whole milli-heat");
      helper.assertTrue(output.stored() + input.stored() == produced, "Heat transfer lost or duplicated stock");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void compressed_air_native_stock_rolls_back(GameTestHelper helper) {
    if (Mods.PNEUMATIC_CRAFT.isLoaded()) {
      var input = coloredStructure(
          helper,
          "test_compressed_air_input_color",
          MachineRegistries.COMPRESSED_AIR_INPUT.get()).hatch();
      var level = helper.getLevel();
      var handler = level.getCapability(PNCCapabilities.AIR_HANDLER_MACHINE, input.getBlockPos(), Direction.NORTH);
      helper.assertTrue(handler != null, "PneumaticCraft air capability was not registered");
      handler.addAir(32_000);
      var port = (CompressedAirPort) input;
      helper.assertTrue(port.air() == 32_000 && port.volume() == 16_000, "Native pressure and recipe stock differ");
      port.acquireAirChange(port.air(), -4_000).resolve(false);
      helper.assertTrue(handler.getAir() == 32_000, "Air input rollback changed the native stock");
      port.acquireAirChange(port.air(), -4_000).resolve(true);
      helper.assertTrue(handler.getAir() == 28_000, "Air debit was not committed to the native handler");
      var output = (CompressedAirPort) coloredStructure(
          helper,
          "test_compressed_air_output_color",
          MachineRegistries.COMPRESSED_AIR_OUTPUT.get()).hatch();
      output.acquireAirChange(output.air(), 4_000).resolve(false);
      helper.assertTrue(output.air() == 0, "Air output rollback left an uncommitted credit");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "legacy_addons")
  public static void world_and_keyed_hatch_colors_resume_and_persist(GameTestHelper helper) {
    color(helper, "aura_output", MachineRegistries.AURA_OUTPUT);
    color(helper, "will_input", MachineRegistries.WILL_INPUT);
    color(helper, "will_output", MachineRegistries.WILL_OUTPUT);
    color(helper, "multi_will_input", MachineRegistries.MULTI_WILL_INPUT);
    color(helper, "multi_will_output", MachineRegistries.MULTI_WILL_OUTPUT);
    color(helper, "vis_input", MachineRegistries.VIS_INPUT);
    color(helper, "vis_output", MachineRegistries.VIS_OUTPUT);
    color(helper, "flux_input", MachineRegistries.FLUX_INPUT);
    color(helper, "flux_output", MachineRegistries.FLUX_OUTPUT);
    color(helper, "life_essence_input_hatch", MachineRegistries.LIFE_ESSENCE_INPUT);
    color(helper, "life_essence_output_hatch", MachineRegistries.LIFE_ESSENCE_OUTPUT);
    color(helper, "meteor_output_hatch", MachineRegistries.METEOR_OUTPUT);
    color(helper, "starlight_input_hatch", MachineRegistries.STARLIGHT_INPUT);
    color(helper, "starlight_input_hatch_trait", MachineRegistries.STARLIGHT_INPUT_TRAIT);
    color(helper, "starlight_input_hatch_brilliant", MachineRegistries.STARLIGHT_INPUT_BRILLIANT);
    color(helper, "starlight_output_hatch", MachineRegistries.STARLIGHT_OUTPUT);
    color(helper, "constellation_hatch", MachineRegistries.CONSTELLATION);
    color(helper, "essentia_input_hatch", MachineRegistries.ESSENTIA_INPUT);
    color(helper, "essentia_output_hatch", MachineRegistries.ESSENTIA_OUTPUT);
    color(helper, "me_essentia_input_bus", MachineRegistries.ME_ESSENTIA_INPUT);
    color(helper, "me_essentia_stock_bus", MachineRegistries.ME_ESSENTIA_STOCK);
    color(helper, "me_essentia_output_bus", MachineRegistries.ME_ESSENTIA_OUTPUT);
    helper.succeed();
  }

  private static void color(GameTestHelper helper, String path, Supplier<? extends Block> block) {
    if (block != null) {
      coloredStructure(helper, "test_" + path + "_color", block.get());
    }
  }

  private static Fixture coloredStructure(GameTestHelper helper, String machineId, Block hatchBlock) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 3));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    var hatchPos = origin.offset(1, 0, 1);
    var state = hatchBlock.defaultBlockState();
    level.setBlockAndUpdate(origin.offset(-1, 0, 1), MachineRegistries.CASINGS.get("plain").get().defaultBlockState());
    level.setBlockAndUpdate(hatchPos, state);
    var hatch = level.getBlockEntity(hatchPos);
    var colored = (MachineColorable) hatch;
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", machineId));
    for (int round = 0; round < 2; round++) {
      colored.setMachineColor(0xFFFFFF);
      StructureService.requestAudit(controller);
      for (int attempt = 0; attempt < 200
          && (!controller.canCommitProduction() || colored.machineColor() != COLOR); attempt++) {
        StructureService.advance(level, 512, 1, 50_000_000L);
      }
      helper.assertTrue(
          controller.canCommitProduction() && colored.machineColor() == COLOR,
          "Hatch color distribution stopped across budget slices");
    }
    var registries = level.registryAccess();
    var restored = BlockEntity.loadStatic(hatchPos, state, hatch.saveWithFullMetadata(registries), registries);
    helper.assertTrue(
        restored instanceof MachineColorable saved
            && saved.machineColorAssigned()
            && saved.machineColor() == COLOR
            && hatch.getUpdateTag(registries).getInt(MachineColorable.COLOR_TAG) == COLOR,
        "Hatch lost its saved or synced color");
    return new Fixture(controller, hatch);
  }

  private record Fixture(MachineControllerBlockEntity controller, BlockEntity hatch) {
  }
}
