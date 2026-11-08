package com.lw.modularmachine.machine.selection;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Snapshot;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.StructureService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-thread capture and detached, conditionally published export
 * work.
 */
@ApiStatus.Internal
public final class RangedSelectionTask {
  private static final Set<RangedSelectionTask> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
  private final ServerPlayer player;
  private final ServerLevel level;
  private final StructureService.StructureSubject controller;
  private final BlockPos origin;
  private final BlockState controllerState;
  private final MachineOrientation orientation;
  private final ItemStack tool;
  private final Snapshot draft;
  private final long epoch;
  private final List<Box> boxes = new ArrayList<>();
  private final Map<BlockPos, DynamicDefinitionEditor.Captured> captured = new LinkedHashMap<>();
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AsyncStructureWork.Permit permit;
  private final Supplier<MachineDefinitionFileStore.Source> loadSource;
  private CompletableFuture<MachineDefinitionFileStore.Source> sourceTask;
  private MachineDefinitionFileStore.Source source;
  private DynamicDefinitionEditor.Input input;
  private CompletableFuture<List<StateOrientationAdapters.BlockReference>> referencesTask;
  private List<StateOrientationAdapters.BlockReference> references;
  private final Map<ResourceLocation, StateOrientationAdapters.BlockSnapshot> registry = new HashMap<>();
  private int registryCursor;
  private CompletableFuture<DynamicDefinitionEditor.Result> editTask;
  private DynamicDefinitionEditor.Result result;
  private CompletableFuture<MachineDefinitionFileStore.SaveResult> saveTask;
  private MachineDefinitionFileStore.SaveResult saved;
  private int boxIndex;
  private Iterator<BlockPos> positions;
  private boolean done;
  private String status = "正在捕获选区…";

  public RangedSelectionTask(ServerPlayer player, StructureService.StructureSubject controller, Snapshot draft, MachineDefinitionFileStore.Catalog catalog) {
    this.player = player;
    level = player.serverLevel();
    this.controller = controller;
    origin = controller.structurePosition().immutable();
    controllerState = level.getBlockState(origin);
    orientation = controller.structureOrientation(level);
    tool = player.getMainHandItem();
    this.draft = draft;
    epoch = StructureService.definitionEpoch();
    if (!draft.dimension().equals(level.dimension().location())) {
      throw new IllegalArgumentException("选区维度已改变，请重新选择");
    }
    if (draft.append() && (catalog == null || draft.fileToken().isEmpty())) {
      throw new IllegalArgumentException("请先选择已有机器文件");
    }
    if (!draft.append()) {
      if (draft.main() == null) {
        throw new IllegalArgumentException("新建机器需要主体选区");
      }
      boxes.add(draft.main());
    }
    for (var segment : draft.segments()) {
      if (segment.body() == null) {
        if (segment.start() != null || segment.end() != null) {
          throw new IllegalArgumentException("首部/尾部需要对应的动态重复体");
        }
        continue;
      }
      boxes.add(segment.body());
      if (segment.start() != null) {
        boxes.add(segment.start());
      }
      if (segment.end() != null) {
        boxes.add(segment.end());
      }
    }
    if (boxes.isEmpty()) {
      throw new IllegalArgumentException("请先选择动态结构");
    }
    long volume = 0;
    for (var box : boxes) {
      volume = Math.addExact(volume, box.volume());
      if (volume > RangedSelectionData.MAX_CAPTURE) {
        throw new IllegalArgumentException("会话总捕获体积不能超过262144个位置");
      }
    }
    permit = AsyncStructureWork.acquireSnapshot();
    if (permit == null) {
      throw new IllegalStateException("结构快照预算暂满，请稍后重试");
    }
    String name = draft.fileName();
    String playerName = player.getGameProfile().getName();
    String token = draft.fileToken();
    boolean append = draft.append();
    var machine = controller.structureMachineId();
    loadSource = () -> {
      try {
        var disk = !append || catalog == null ? MachineDefinitionFileStore.scan() : catalog;
        var loaded = append ? MachineDefinitionFileStore.read(disk, token) : MachineDefinitionFileStore
            .create(disk, name, playerName);
        if (append && !machine.equals(loaded.machine())) {
          throw new IllegalArgumentException("控制器与所选机器文件不对应");
        }
        return loaded;
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    };
    ACTIVE.add(this);
  }

  /** At most 128 world positions per turn, under the caller's shared deadline. */
  public boolean advance(long deadline) {
    if (done) {
      return true;
    }
    try {
      // A completed disk publication must still be reported even if the player moved
      // afterward.
      if (saveTask != null && saveTask.isDone()) {
        saved = saveTask.join();
        finish(
            "已保存 " + saved.file() + "；主体 " + result.mainParts() + "，动态 " + result.dynamicParts() + "，去重 " + result
                .deduplicated() + "，排除控制器 " + result.excludedController() + (draft
                    .append() ? "。使用 /mmcece reload 生效。" : "。新增机器需完整重启双端。") + "\n源SHA-256: " + saved
                        .sourceHash() + "\n目标SHA-256: " + saved
                            .targetHash() + (saved.backup() == null ? "" : "\n备份: " + saved.backup()));
        return true;
      }
      if (!valid()) {
        cancel();
        return done;
      }
      if (sourceTask == null) {
        sourceTask = AsyncStructureWork.submit(loadSource);
      }
      int work = 0;
      while (boxIndex < boxes.size() && work < 128 && System.nanoTime() < deadline) {
        if (positions == null) {
          var box = boxes.get(boxIndex);
          positions = BlockPos.betweenClosed(
              RangedSelectionService.min(box.first(), box.second()),
              RangedSelectionService.max(box.first(), box.second())).iterator();
        }
        if (!positions.hasNext()) {
          boxIndex++;
          positions = null;
          continue;
        }
        var pos = positions.next().immutable();
        work++;
        if (!level.hasChunkAt(pos) || !level.isInWorldBounds(pos)) {
          throw new IllegalArgumentException("选区含未加载区块或越界位置：" + pos.toShortString());
        }
        if (!level.mayInteract(player, pos)) {
          throw new IllegalArgumentException("无权读取所选位置：" + pos.toShortString());
        }
        if (!captured.containsKey(pos)) {
          var state = level.getBlockState(pos);
          var local = orientation.toLocalOffset(
              new BlockPos(
                  Math.subtractExact(pos.getX(), origin.getX()),
                  Math.subtractExact(pos.getY(), origin.getY()),
                  Math.subtractExact(pos.getZ(), origin.getZ())));
          captured.put(
              pos,
              new DynamicDefinitionEditor.Captured(
                  pos,
                  state,
                  RangedSelectionService.exportPart(state, orientation, local),
                  fields(pos)));
        }
      }
      if (boxIndex < boxes.size() || sourceTask == null || !sourceTask.isDone()) {
        status = "正在捕获选区：" + captured.size() + " 个位置";
        return false;
      }
      if (source == null) {
        source = sourceTask.join();
        input = new DynamicDefinitionEditor.Input(
            source.machine(),
            source.root(),
            source.variables(),
            orientation,
            origin,
            draft.append() ? null : draft.main(),
            draft.segments(),
            draft.deduplicate(),
            draft.includeAir(),
            List.copyOf(captured.values()));
      }
      if (referencesTask == null) {
        var frozen = input;
        referencesTask = AsyncStructureWork.submit(() -> DynamicDefinitionEditor.references(frozen));
      }
      if (referencesTask == null || !referencesTask.isDone()) {
        return false;
      }
      if (references == null) {
        references = referencesTask.join();
      }
      status = "正在校验候选与全部动态长度…";
      int groups = 0;
      while (registryCursor < references.size() && groups++ < 128 && System.nanoTime() < deadline) {
        var reference = references.get(registryCursor++);
        registry.putAll(StateOrientationAdapters.capture(List.of(reference)).blocks());
      }
      if (registryCursor < references.size()) {
        return false;
      }
      if (editTask == null) {
        var frozen = input;
        var descriptors = new StateOrientationAdapters.RegistrySnapshot(registry);
        editTask = AsyncStructureWork.submit(() -> DynamicDefinitionEditor.edit(frozen, descriptors));
      }
      if (editTask == null || !editTask.isDone()) {
        return false;
      }
      if (result == null) {
        result = editTask.join();
      }
      if (saveTask == null) {
        if (!valid()) {
          cancel();
          return done;
        }
        var frozenSource = source;
        var json = result.root();
        var permit = active;
        long expectedEpoch = epoch;
        saveTask = AsyncStructureWork.submit(() -> {
          try {
            return MachineDefinitionFileStore
                .save(frozenSource, json, () -> permit.get() && expectedEpoch == StructureService.definitionEpoch());
          } catch (IOException failure) {
            throw new UncheckedIOException(failure);
          }
        });
        status = "正在备份并原子写入文件…";
      }
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      active.set(false);
      finish("导出失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
    }
    return done;
  }

  private Map<String, Long> fields(BlockPos pos) {
    if (!(level.getBlockEntity(pos) instanceof MachinePortBlockEntity port) || !port.schemaSupported()) {
      return Map.of();
    }
    long items = 0;
    for (int index = 0; index < port.inventory().getSlots(); index++) {
      items = Math.addExact(items, port.inventory().getStackInSlot(index).getCount());
    }
    return Map.of(
        "energy_stored",
        port.energy().stored(),
        "fluid_amount",
        (long) port.fluidTank().getFluidAmount(),
        "item_count",
        items);
  }

  private boolean valid() {
    return active.get()
        && player.isAlive()
        && !player.hasDisconnected()
        && player.hasPermissions(2)
        && player.serverLevel() == level
        && player.getMainHandItem() == tool
        && tool.getItem() instanceof RangedConstructToolItem
        && StructureService.definitionEpoch() == epoch
        && !controller.structureRemoved()
        && level.hasChunkAt(origin)
        && level.getBlockEntity(origin) == controller
        && level.getBlockState(origin) == controllerState
        && controller.structureOrientation(level).equals(orientation)
        && level.mayInteract(player, origin);
  }

  public void cancel() {
    active.set(false);
    if (saveTask != null && !saveTask.isDone()) {
      status = "正在结束文件写入，请稍候…";
      return;
    }
    finish("导出已取消：选区、控制器、工具或定义已改变，请重试");
  }

  /**
   * Closing the owning session revokes publication and releases main-thread
   * ownership.
   */
  public void abandon() {
    active.set(false);
    ACTIVE.remove(this);
    permit.close();
  }

  private void finish(String message) {
    status = message;
    done = true;
    ACTIVE.remove(this);
    permit.close();
  }

  public boolean done() {
    return done;
  }
  public String status() {
    return status;
  }
  public MachineDefinitionFileStore.SaveResult saved() {
    return saved;
  }
  public DynamicDefinitionEditor.Result result() {
    return result;
  }

  /**
   * Called from the existing authoritative block-change hook, including A -> B ->
   * A changes.
   */
  public static void blockChanged(ServerLevel level, BlockPos pos) {
    for (var task : ACTIVE) {
      if (task.level == level && (task.origin.equals(pos) || task.boxes.stream().anyMatch(box -> contains(box, pos)))) {
        task.active.set(false);
      }
    }
  }

  public static void resourceChanged(ServerLevel level, BlockPos pos) {
    for (var task : ACTIVE) {
      if (task.level == level
          && task.draft.append()
          && task.captured.containsKey(pos)
          && !task.captured.get(pos).fields().isEmpty()) {
        task.active.set(false);
      }
    }
  }

  public static void chunkUnloaded(ServerLevel level, ChunkPos chunk) {
    for (var task : ACTIVE) {
      if (task.level != level) {
        continue;
      }
      for (var box : task.boxes) {
        var min = RangedSelectionService.min(box.first(), box.second());
        var max = RangedSelectionService.max(box.first(), box.second());
        if (chunk.x >= min.getX() >> 4
            && chunk.x <= max.getX() >> 4
            && chunk.z >= min.getZ() >> 4
            && chunk.z <= max.getZ() >> 4) {
          task.active.set(false);
          break;
        }
      }
    }
  }

  public static void stop() {
    for (var task : List.copyOf(ACTIVE)) {
      task.abandon();
    }
  }

  private static boolean contains(Box box, BlockPos pos) {
    return pos.getX() >= Math.min(box.first().getX(), box.second().getX())
        && pos.getX() <= Math.max(box.first().getX(), box.second().getX())
        && pos.getY() >= Math.min(box.first().getY(), box.second().getY())
        && pos.getY() <= Math.max(box.first().getY(), box.second().getY())
        && pos.getZ() >= Math.min(box.first().getZ(), box.second().getZ())
        && pos.getZ() <= Math.max(box.first().getZ(), box.second().getZ());
  }
}
