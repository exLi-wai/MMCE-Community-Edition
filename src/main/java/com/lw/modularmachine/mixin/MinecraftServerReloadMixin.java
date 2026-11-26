package com.lw.modularmachine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lw.modularmachine.command.MachineReloadRequest;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerReloadMixin {
  @Unique
  private final AtomicReference<CompletableFuture<Void>> MMCECE$reloadTail = new AtomicReference<>();

  // NeoForge shares a DataMapLoader between prepare and updateRegistryTags.
  // managedBlock can dispatch another command anywhere between those phases.
  @WrapMethod(method = "reloadResources")
  private CompletableFuture<Void> MMCECE$sequenceReloads(Collection<String> selectedPacks, Operation<CompletableFuture<Void>> reload) {
    var server = (MinecraftServer) (Object) this;
    var finished = new CompletableFuture<Void>();
    CompletableFuture<Void> previous;
    if (selectedPacks instanceof MachineReloadRequest) {
      previous = MMCECE$reloadTail.getAndSet(finished);
    } else {
      previous = MMCECE$reloadTail.compareAndExchange(null, finished);
      if (previous != null) {
        // An external listener can join its returned future while the active
        // reload waits for that listener. Queuing that call would deadlock.
        return CompletableFuture.failedFuture(new IllegalStateException("Server resource reload already in progress"));
      }
    }
    try {
      CompletableFuture<Void> result;
      if (previous == null) {
        result = reload.call(selectedPacks);
      } else {
        // A preceding /datapack command may have changed the active packs since this
        // machine reload was queued.
        result = previous.thenComposeAsync(
            ignored -> reload.call(server.getPackRepository().getSelectedIds()),
            server::executeIfPossible);
      }
      return result.whenComplete((ignored, failure) -> {
        try {
          if (failure == null && selectedPacks instanceof MachineReloadRequest request) {
            request.recordDefinitionResult(server.getRecipeManager());
          }
        } finally {
          MMCECE$finishReload(finished);
        }
      }).copy();
    } catch (RuntimeException | Error failure) {
      MMCECE$finishReload(finished);
      throw failure;
    }
  }

  @Unique
  private void MMCECE$finishReload(CompletableFuture<Void> finished) {
    MMCECE$reloadTail.compareAndSet(finished, null);
    // Each command receives its own result. A rejected reload still releases
    // the next request. Cancellation of a caller's copy cannot release it early.
    finished.complete(null);
  }
}
