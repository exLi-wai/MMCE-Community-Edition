package com.lw.modularmachine.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortRecipePlan;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.network.NetworkPortPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_placement")
@PrefixGameTestTemplate(false)
public final class NetworkPortRecipeTransferGameTest {
  @GameTest(template = "empty16")
  public static void smelting_input_with_any_components_configures_its_declared_sword(GameTestHelper helper) {
    var sword = new ItemStack(Items.IRON_SWORD);
    var oxygen = ResourceLocation.parse("mekanism:oxygen");
    var source = MachineRecipeBuilder
        .create(ResourceLocation.parse("mmcece:mmcece/test_sword"), ResourceLocation.parse("mmcece:ae"), 20)
        .itemInputIgnoringComponents(sword, 1).chemicalInput(oxygen, 1000)
        .itemOutput(new ItemStack(Items.IRON_NUGGET), 1).toJson(helper.getLevel().registryAccess());
    var items = plan(helper, source, ResourceType.ITEM);
    var pos = helper.absolutePos(new BlockPos(2, 2, 2));
    var level = helper.getLevel();
    level.setBlockAndUpdate(pos, MachineRegistries.NETWORK_PORTS.get("me_item_input_bus").get().defaultBlockState());
    var port = (NetworkPortBlockEntity) level.getBlockEntity(pos);
    helper.assertTrue(port.configureRecipe(items, port.configRevision()), "ME bus rejected the smelting input");
    var row = port.snapshot().rows().getFirst();
    helper.assertTrue(
        row.target() == 1 && new NetworkPortResource.Item(sword).equals(row.configured()),
        "ME bus must request exactly one declared sword");
    var chemicals = plan(helper, source, ResourceType.CHEMICAL);
    helper.assertTrue(
        chemicals.targets()
            .equals(List.of(new NetworkPortRecipePlan.Target(new NetworkPortResource.Chemical(oxygen), 1000))),
        "Chemical transfer must still request only the declared oxygen");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void published_recipe_transfers_only_through_the_current_port_menu(GameTestHelper helper) {
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(3, 2, 3));
    level.setBlockAndUpdate(
        position,
        MachineRegistries.NETWORK_PORTS.get("me_item_input_bus").get().defaultBlockState());
    var port = (NetworkPortBlockEntity) level.getBlockEntity(position);
    var player = FakePlayerFactory.get(level, new GameProfile(new UUID(0, 0xAECE1), "port_transfer"));
    player.setGameMode(GameType.SURVIVAL);
    player.setPos(position.getX() + .5, position.getY() + .5, position.getZ() + 1.5);
    var menu = (NetworkPortMenu) port.createMenu(7, player.getInventory(), player);
    player.containerMenu = menu;
    try {
      var dirtRecipe = ResourceLocation.parse("mmcece:mmcece/test_native_a");
      var gravelRecipe = ResourceLocation.parse("mmcece:mmcece/test_energy_paid_gravel");
      helper.assertTrue(
          MachineRecipeService.definition(level.getServer(), dirtRecipe).isPresent()
              && MachineRecipeService.definition(level.getServer(), gravelRecipe).isPresent(),
          "Missing native recipe fixtures");
      menu.broadcastChanges();
      var status = menu.status();
      helper.assertTrue(status != null && status.editable(), "Server never published the editable port menu");
      var initial = status.snapshot().configRevision();
      var dirt = new NetworkPortResource.Item(new ItemStack(Items.DIRT));
      var gravel = new NetworkPortResource.Item(new ItemStack(Items.GRAVEL));
      var dirtTargets = List.of(new NetworkPortRecipePlan.Target(dirt, 2));
      var gravelTargets = List.of(new NetworkPortRecipePlan.Target(gravel, 1));
      boolean applied = menu.apply(
          player,
          new NetworkPortPayloads.RecipeAction(
              menu.containerId,
              position,
              status.session(),
              initial,
              dirtRecipe,
              1,
              dirtTargets));
      var updated = port.snapshot();
      helper.assertTrue(
          applied
              && updated.configRevision() == initial + 1
              && updated.rows().getFirst().target() == 2
              && dirt.equals(updated.rows().getFirst().configured()),
          "Server did not resolve the native recipe into its declared filter");
      boolean staleRevision = menu.apply(
          player,
          new NetworkPortPayloads.RecipeAction(
              menu.containerId,
              position,
              status.session(),
              initial,
              gravelRecipe,
              2,
              gravelTargets));
      helper.assertTrue(
          !staleRevision && updated.equals(port.snapshot()),
          "A stale revision replaced the dirt configuration");
      var namedGravel = new ItemStack(Items.GRAVEL);
      namedGravel.set(DataComponents.CUSTOM_NAME, Component.literal("Old recipe input"));
      var oldQuantity = List.of(new NetworkPortRecipePlan.Target(gravel, 8));
      var oldComponents = List.of(new NetworkPortRecipePlan.Target(new NetworkPortResource.Item(namedGravel), 1));
      for (var oldTargets : List.of(dirtTargets, oldQuantity, oldComponents)) {
        var stalePreview = new NetworkPortPayloads.RecipeAction(
            menu.containerId,
            position,
            status.session(),
            updated.configRevision(),
            gravelRecipe,
            3,
            oldTargets);
        helper.assertTrue(
            !menu.apply(player, stalePreview) && updated.equals(port.snapshot()),
            "A current revision with an obsolete recipe preview replaced the port configuration");
      }
      var delayed = new NetworkPortPayloads.RecipeAction(
          menu.containerId,
          position,
          status.session(),
          updated.configRevision(),
          gravelRecipe,
          4,
          gravelTargets);
      player.closeContainer();
      helper.assertTrue(
          !menu.apply(player, delayed) && updated.equals(port.snapshot()),
          "A closed menu remained authorized to configure the port");
    } finally {
      if (player.containerMenu == menu) {
        player.closeContainer();
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void recipe_configuration_replaces_all_targets_once_and_preserves_buffers(GameTestHelper helper) {
    var level = helper.getLevel();
    var pos = helper.absolutePos(new BlockPos(2, 2, 2));
    level.setBlockAndUpdate(pos, MachineRegistries.NETWORK_PORTS.get("me_item_input_bus").get().defaultBlockState());
    var port = (NetworkPortBlockEntity) level.getBlockEntity(pos);
    var stone = new NetworkPortResource.Item(new ItemStack(Items.STONE));
    var dirt = new NetworkPortResource.Item(new ItemStack(Items.DIRT));
    port.configure(0, stone, 12, port.configRevision());
    port.inventory().setStackInSlot(0, new ItemStack(Items.STONE, 7));
    var source = source();
    var inputs = source.getAsJsonArray("inputs");
    inputs.add(inputs.get(0).deepCopy());
    var plan = plan(helper, source, ResourceType.ITEM);
    long revision = port.configRevision();
    helper.assertTrue(port.configureRecipe(plan, revision), "Valid bulk configuration was rejected");
    var snapshot = port.snapshot();
    helper.assertTrue(snapshot.configRevision() == revision + 1, "Bulk edit did not publish exactly once");
    helper.assertTrue(
        dirt.equals(snapshot.rows().get(1).configured()) && snapshot.rows().get(1).target() == 4,
        "Coalesced inputs must use the first empty slot");
    helper.assertTrue(
        snapshot.rows().getFirst().configured() == null
            && snapshot.rows().getFirst().stored() == 7
            && stone.equals(snapshot.rows().getFirst().buffered()),
        "Replacing filters discarded buffered items");
    inputs.get(0).getAsJsonObject().addProperty("amount", 11);
    helper.assertTrue(
        !port.configureRecipe(plan(helper, source, ResourceType.ITEM), revision),
        "Stale edit was accepted");
    inputs.get(0).getAsJsonObject().addProperty("amount", Integer.MAX_VALUE);
    var excessive = plan(helper, source, ResourceType.ITEM);
    helper.assertTrue(
        excessive.error() == NetworkPortRecipePlan.Error.AMOUNT_OVERFLOW
            && !port.configureRecipe(excessive, port.configRevision())
            && snapshot.equals(port.snapshot()),
        "Rejected edit changed filter or buffer state");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void fluid_targets_reuse_matching_buffers_before_empty_and_occupied_tanks(GameTestHelper helper) {
    var level = helper.getLevel();
    var pos = helper.absolutePos(new BlockPos(2, 2, 2));
    level.setBlockAndUpdate(pos, MachineRegistries.NETWORK_PORTS.get("me_fluid_input_bus").get().defaultBlockState());
    var port = (NetworkPortBlockEntity) level.getBlockEntity(pos);
    var lava = new FluidStack(Fluids.LAVA, 40);
    var lavaKey = new NetworkPortResource.Fluid(lava);
    var waterKey = new NetworkPortResource.Fluid(new FluidStack(Fluids.WATER, 1));
    for (int slot = 0; slot < 8; slot++) {
      port.fluidTank(slot).setFluid(lava);
    }
    var source = source();
    source.add("fluid_inputs", JsonParser.parseString("""
        [{"id":"minecraft:water","amount":10},{"id":"minecraft:lava","amount":20}]
        """));
    var plan = plan(helper, source, ResourceType.FLUID);
    helper.assertTrue(port.configureRecipe(plan, port.configRevision()), "Fluid configuration was rejected");
    var rows = port.snapshot().rows();
    helper.assertTrue(
        lavaKey.equals(rows.getFirst().configured())
            && rows.getFirst().target() == 20
            && waterKey.equals(rows.get(8).configured())
            && rows.get(8).target() == 10,
        "Lava must keep a matching tank and water must use the empty tank");
    port.fluidTank(8).setFluid(lava);
    helper.assertTrue(port.configureRecipe(plan, port.configRevision()), "Full tank configuration was rejected");
    rows = port.snapshot().rows();
    helper.assertTrue(
        lavaKey.equals(rows.getFirst().configured())
            && waterKey.equals(rows.get(1).configured())
            && rows.get(8).configured() == null
            && rows.stream().allMatch(row -> lavaKey.equals(row.buffered()) && row.stored() == 40),
        "An earlier unmatched target displaced a match or changed the stored fluid");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void recipe_targets_include_tick_fees_and_reject_unrepresentable_inputs(GameTestHelper helper) {
    var source = source();
    source.add("fluid_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":10}]"));
    source.add("fluid_per_tick_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":3}]"));
    var fluids = plan(helper, source, ResourceType.FLUID);
    helper.assertTrue(
        fluids.error() == NetworkPortRecipePlan.Error.NONE
            && fluids.targets().size() == 1
            && fluids.targets().getFirst().amount() == 70,
        "Missing total duration or initial fluid demand");
    var tick = source.getAsJsonArray("fluid_per_tick_inputs").get(0).getAsJsonObject();
    tick.addProperty("selector", "second");
    helper.assertTrue(
        plan(helper, source, ResourceType.FLUID).error() == NetworkPortRecipePlan.Error.UNSUPPORTED,
        "Combined different physical routes into one port");
    tick.addProperty("selector", "");
    tick.addProperty("amount", Integer.MAX_VALUE);
    helper.assertTrue(
        plan(helper, source, ResourceType.FLUID).error() == NetworkPortRecipePlan.Error.AMOUNT_OVERFLOW,
        "Tick quantity overflow was not rejected");
    source.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("chance", 0.5);
    helper.assertTrue(
        plan(helper, source, ResourceType.ITEM).error() == NetworkPortRecipePlan.Error.UNSUPPORTED,
        "Converted probabilistic inputs into fixed quantities");
    helper.assertTrue(
        plan(helper, source, ResourceType.CHEMICAL).error() == NetworkPortRecipePlan.Error.NO_INPUT,
        "Other input domains prevented handling an empty chemical input");
    var rows = new JsonArray();
    for (var id : List
        .of("dirt", "stone", "sand", "gravel", "clay", "iron_ingot", "flint", "cobblestone", "coal", "stick")) {
      var row = new JsonObject();
      var stack = new JsonObject();
      stack.addProperty("id", "minecraft:" + id);
      row.add("stack", stack);
      row.addProperty("amount", 1);
      rows.add(row);
    }
    source.add("inputs", rows);
    helper.assertTrue(
        plan(helper, source, ResourceType.ITEM).error() == NetworkPortRecipePlan.Error.TOO_MANY,
        "Truncated excess distinct inputs");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void chemical_targets_merge_full_tick_cost_and_reject_excess_quantity(GameTestHelper helper) {
    var source = source();
    var chemicals = JsonParser.parseString("""
        {"inputs":[{"chemical":"mekanism:oxygen","amount":7,"selector":"gas"}],
         "per_tick":[{"chemical":"mekanism:oxygen","amount":3,"selector":"gas"}],
         "outputs":[{"chemical":"mekanism:hydrogen","amount":2}]}
        """).getAsJsonObject();
    source.add("chemicals", chemicals);
    var result = plan(helper, source, ResourceType.CHEMICAL);
    var oxygen = new NetworkPortResource.Chemical(ResourceLocation.parse("mekanism:oxygen"));
    helper.assertTrue(
        result.error() == NetworkPortRecipePlan.Error.NONE
            && result.targets().equals(List.of(new NetworkPortRecipePlan.Target(oxygen, 67))),
        "Chemical targets must include initial and all tick inputs without counting outputs");
    var tick = chemicals.getAsJsonArray("per_tick").get(0).getAsJsonObject();
    tick.addProperty("amount", Long.MAX_VALUE);
    var excessive = plan(helper, source, ResourceType.CHEMICAL);
    helper.assertTrue(
        excessive.error() == NetworkPortRecipePlan.Error.AMOUNT_OVERFLOW && excessive.targets().isEmpty(),
        "Unrepresentable chemical quantity produced partial or wrapped targets");
    helper.succeed();
  }

  private static JsonObject source() {
    return JsonParser.parseString("""
        {"machine":"mmcece:test_transfer","duration":20,
         "inputs":[{"stack":{"id":"minecraft:dirt"},"amount":2}],
         "outputs":[{"stack":{"id":"minecraft:stone"},"amount":1}]}
        """).getAsJsonObject();
  }

  private static NetworkPortRecipePlan plan(GameTestHelper helper, JsonObject source, ResourceType kind) {
    var operations = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
    return NetworkPortRecipePlan.forRecipe(MachineRecipe.CODEC.codec().parse(operations, source).getOrThrow(), kind);
  }
}
