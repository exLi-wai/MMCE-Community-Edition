package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.google.gson.JsonParser;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.machine.upgrade.UpgradeDefinition;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.io.FileUtils;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_placement")
@PrefixGameTestTemplate(false)
public final class EmptyFactoryCatalogGameTest {
  private static final String BATCH = "empty_factory_catalog_reload";
  private static Path pack;
  private static List<String> originalPacks;

  @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 600)
  public static void empty_catalog_preserves_thread_cursor_and_missing_lock(GameTestHelper helper) {
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(7, 5, 7));
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "test_axis");
    level.setBlockAndUpdate(position, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    level.setBlockAndUpdate(
        position.east(),
        Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
    var factory = (FactoryControllerBlockEntity) level.getBlockEntity(position);
    factory.bindMachine(machine);
    var threads = factory.threadConfiguration();
    var idle = new FactoryThreadState(
        "idle",
        machine,
        null,
        true,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        UpgradeSnapshot.identity(0));
    var locked = new FactoryThreadState(
        "locked",
        machine,
        ResourceLocation.fromNamespaceAndPath("mmcece", "mmcece/first_factory_recipe"),
        true,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        UpgradeSnapshot.identity(0));
    threads.replaceThreads(List.of(idle, locked));
    threads.visitWindow(1);
    int cursor = factory.scheduler().cursor();
    helper.startSequence().thenWaitUntil(() -> {
      var scheduler = factory.scheduler();
      helper.assertTrue(
          scheduler.channel("idle").reason() == FactoryThreadState.Reason.NO_RECIPE,
          "Empty machine did not report NO_RECIPE");
      helper.assertTrue(
          MachineRecipeService.catalogFor(factory).orElseThrow().recipes().size() == 0,
          "Expected a published empty catalog");
      helper.assertTrue(
          scheduler.channel("locked").reason() == FactoryThreadState.Reason.LOCKED_RECIPE_MISSING,
          "Empty catalog overrode the missing lock");
      for (int count = 0; count < 20; count++) {
        factory.serverTick();
      }
      helper.assertTrue(
          factory.scheduler() == scheduler && scheduler.cursor() == cursor && scheduler.waitingAges().isEmpty(),
          "An empty machine rotated the search window or changed the idle scheduler");
    }).thenExecute(() -> {
      int previousBudget = MachineConfig.FACTORY_THREADS_PER_TICK.get();
      try {
        MachineConfig.FACTORY_THREADS_PER_TICK.set(1);
        threads.replaceThreads(List.of(idle, locked));
        threads.visitWindow(1);
        threads.recordAttempt("idle", false);
        int initialCursor = threads.scheduler().cursor();
        var initialOrder = threads.scheduler().roundOrder();
        var initialAges = threads.scheduler().waitingAges();
        for (int visited = 1; visited <= 2; visited++) {
          factory.serverTick();
          var scheduler = threads.scheduler();
          long updated = scheduler.channels().stream()
              .filter(thread -> thread.reason() != FactoryThreadState.Reason.NONE).count();
          helper.assertTrue(updated == visited, "Empty catalog must update one thread per visit");
          helper.assertTrue(
              scheduler.cursor() == initialCursor
                  && scheduler.roundOrder().equals(initialOrder)
                  && scheduler.waitingAges().equals(initialAges),
              "Gate updates consumed production fairness state");
        }
      } finally {
        MachineConfig.FACTORY_THREADS_PER_TICK.set(previousBudget);
      }
    }).thenExecute(() -> publishFirstRecipe(level)).thenWaitUntil(() -> {
      helper.assertTrue(level.getBlockEntity(position) == factory, "Reload replaced the factory under test");
      var scheduler = factory.scheduler();
      for (var id : List.of("idle", "locked")) {
        helper.assertTrue(
            scheduler.channel(id).reason() == FactoryThreadState.Reason.MISSING_ITEM_INPUT,
            id + " did not search the first published recipe: " + scheduler.channel(id).reason());
        helper
            .assertTrue(scheduler.waitingAges().getOrDefault(id, 0) > 0, id + " did not attempt a search after reload");
      }
    }).thenSucceed();
  }

  @GameTest(template = "empty16", batch = "factory_upgrade_lock_diagnostics", timeoutTicks = 600)
  public static void upgrade_conflict_precedes_missing_locks_until_recovery(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(4, 5, 7));
    var empty = UpgradeBusGameTests
        .buildUpgradeFactory(helper, origin, ResourceLocation.parse("mmcece:test_upgrade_bus_binding"));
    var emptyBus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
    level.setBlockAndUpdate(origin.south().east(), Blocks.STONE_BRICKS.defaultBlockState());
    var other = origin.east(6);
    var active = UpgradeBusGameTests
        .buildUpgradeFactory(helper, other, ResourceLocation.parse("mmcece:test_upgrade_recipe_checks"));
    var activeBus = UpgradeBusGameTests.placeBus(helper, other.south(), "normal");
    level.setBlockAndUpdate(
        other.south().west(),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        other.south().east(),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    helper.startSequence().thenWaitUntil(() -> {
      helper.assertTrue(empty.canCommitStructure() && active.canCommitStructure(), "Factory structures not confirmed");
      helper.assertTrue(
          MachineRecipeService.catalogFor(empty).map(catalog -> catalog.recipes().size() == 0).orElse(false)
              && MachineRecipeService.catalogFor(active).map(catalog -> catalog.recipes().size() > 0).orElse(false),
          "Expected published empty and nonempty factory catalogs");
    }).thenExecute(() -> {
      var savedCatalog = UpgradeCatalogService.snapshot();
      int savedBudget = MachineConfig.FACTORY_THREADS_PER_TICK.get();
      try {
        UpgradeCatalogService.replaceDefinitions(
            List.of(
                UpgradeDefinition
                    .parse(ResourceLocation.parse("mmcece:test_limited_upgrade"), JsonParser.parseString("""
                        {"schema_version":1,"item":"minecraft:amethyst_shard","max_count":1}
                        """))));
        MachineConfig.FACTORY_THREADS_PER_TICK.set(1);
        checkConflictingLocks(helper, empty, emptyBus, true);
        checkConflictingLocks(helper, active, activeBus, false);
      } finally {
        MachineConfig.FACTORY_THREADS_PER_TICK.set(savedBudget);
        UpgradeCatalogService
            .replaceDefinitions(savedCatalog.definitions(), savedCatalog.types(), savedCatalog.supportedItems());
      }
    }).thenSucceed();
  }

  private static void checkConflictingLocks(GameTestHelper helper, FactoryControllerBlockEntity factory, UpgradeBusBlockEntity bus, boolean empty) {
    var missing = ResourceLocation.parse("mmcece:absent_locked_recipe");
    var threads = List.of("first", "second").stream()
        .map(
            id -> new FactoryThreadState(
                id,
                factory.machineId(),
                missing,
                true,
                0,
                1,
                0,
                FactoryThreadState.State.IDLE,
                FactoryThreadState.Reason.NONE,
                0,
                UpgradeSnapshot.identity(0)))
        .toList();
    factory.threadConfiguration().replaceThreads(threads);
    bus.inventory().setStackInSlot(0, new ItemStack(Items.AMETHYST_SHARD, 2));
    bus.serverTick();
    for (int tick = 0; tick < 3; tick++) {
      factory.serverTick();
      helper.assertTrue(
          !factory.upgrades().valid()
              && factory.scheduler().channels().stream()
                  .allMatch(thread -> thread.reason() == FactoryThreadState.Reason.UPGRADE_CONFLICT),
          "Recipe locks overwrote the active upgrade conflict");
    }
    bus.inventory().setStackInSlot(0, ItemStack.EMPTY);
    bus.serverTick();
    for (int tick = 1; tick <= 2; tick++) {
      factory.serverTick();
      long missingLocks = factory.scheduler().channels().stream()
          .filter(thread -> thread.reason() == FactoryThreadState.Reason.LOCKED_RECIPE_MISSING).count();
      helper.assertTrue(
          factory.upgrades().valid() && missingLocks == (empty ? tick : 2),
          "Recovery did not recheck missing locks within the catalog's gate budget");
    }
  }

  private static void publishFirstRecipe(ServerLevel level) {
    var server = level.getServer();
    var repository = server.getPackRepository();
    originalPacks = List.copyOf(repository.getSelectedIds());
    try {
      pack = Files.createTempDirectory(server.getWorldPath(LevelResource.DATAPACK_DIR), "mmcece-first-factory-recipe-");
      int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
      Files.writeString(
          pack.resolve("pack.mcmeta"),
          "{\"pack\":{\"pack_format\":" + format + ",\"description\":\"First factory recipe test\"}}");
      var recipes = Files.createDirectories(pack.resolve("data/mmcece/recipe/mmcece"));
      Files.writeString(recipes.resolve("first_factory_recipe.json"), """
          {
            "type": "mmcece:machine", "machine": "mmcece:test_axis", "duration": 1,
            "inputs": [{"stack": {"id": "minecraft:stone"}, "amount": 1}],
            "outputs": [{"stack": {"id": "minecraft:dirt"}, "amount": 1}]
          }
          """);
      var selected = new ArrayList<>(originalPacks);
      repository.reload();
      selected.add("file/" + pack.getFileName());
      server.reloadResources(selected).join();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  @AfterBatch(batch = BATCH)
  public static void restorePacks(ServerLevel level) throws IOException {
    if (pack != null) {
      FileUtils.deleteDirectory(pack.toFile());
      pack = null;
      var server = level.getServer();
      server.getPackRepository().reload();
      server.reloadResources(originalPacks).join();
      originalPacks = null;
    }
  }
}
