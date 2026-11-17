package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Coalesced main-thread presentation sends after transactions and callbacks.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece")
public final class ControllerVisualDataSync {
  private static final ArrayDeque<ControllerScriptStateOwner> QUEUE = new ArrayDeque<>();
  private static final Set<ControllerScriptStateOwner> PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
  private static final int MAX_BYTES = 131_072;

  private ControllerVisualDataSync() {
  }

  public static void schedule(ControllerScriptStateOwner owner) {
    if (owner.structureLevel() instanceof ServerLevel level) {
      ServerThreadGuard.require(level, "Controller visual data scheduled");
      if (PENDING.add(owner)) {
        QUEUE.addLast(owner);
      }
    }
  }

  public static void remove(ControllerScriptStateOwner owner) {
    if (owner.structureLevel() instanceof ServerLevel level) {
      ServerThreadGuard.require(level, "Controller visual data removed");
      if (PENDING.remove(owner)) {
        QUEUE.removeIf(candidate -> candidate == owner);
      }
    }
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    long deadline = System.nanoTime() + 500_000L;
    int bytes = 0;
    for (int work = 0; work < 32 && System.nanoTime() < deadline; work++) {
      var owner = QUEUE.peekFirst();
      if (owner == null) {
        return;
      }
      if (!(owner.structureLevel() instanceof ServerLevel level)
          || level.getServer() != event.getServer()
          || owner.structureRemoved()
          || !level.hasChunkAt(owner.structurePosition())
          || level.getBlockEntity(owner.structurePosition()) != owner
          || !owner.matchesStructureBlock(level.getBlockState(owner.structurePosition()))) {
        QUEUE.removeFirst();
        PENDING.remove(owner);
        continue;
      }
      int size = owner.scriptState().snapshot().customData().getBytes(StandardCharsets.UTF_8).length;
      if (bytes + size > MAX_BYTES) {
        return;
      }
      QUEUE.removeFirst();
      PENDING.remove(owner);
      bytes += size;
      var state = level.getBlockState(owner.structurePosition());
      level.sendBlockUpdated(owner.structurePosition(), state, state, Block.UPDATE_CLIENTS);
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    QUEUE.clear();
    PENDING.clear();
  }
}
