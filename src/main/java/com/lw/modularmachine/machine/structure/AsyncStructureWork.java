package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.machine.orientation.MachineOrientation;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * Bounded pure work only. No task submitted here may capture a live world or
 * BE.
 */
public final class AsyncStructureWork {
  private static ThreadPoolExecutor executor;
  private static final Semaphore SNAPSHOTS = new Semaphore(8);
  private static final Set<CompletableFuture<?>> TASKS = ConcurrentHashMap.newKeySet();
  private static long templateEpoch;
  private static final LinkedHashMap<Key, CompiledStructure> TEMPLATES = new LinkedHashMap<>(16, .75F, true);

  private AsyncStructureWork() {
  }

  private record Key(MachineDefinition definition, MachineOrientation orientation) {
    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(definition) + orientation.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key && key.definition == definition && key.orientation == orientation;
    }
  }

  public static synchronized <T> CompletableFuture<T> submit(Supplier<T> task) {
    if (executor == null) {
      executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
        var thread = new Thread(runnable, "mmcece-structure");
        thread.setDaemon(true);
        return thread;
      });
      executor.allowCoreThreadTimeOut(true);
    }
    var result = new CompletableFuture<T>();
    TASKS.add(result);
    result.whenComplete((value, failure) -> TASKS.remove(result));
    try {
      executor.execute(() -> {
        if (result.isCancelled()) {
          return;
        }
        try {
          result.complete(task.get());
        } catch (Throwable failure) {
          result.completeExceptionally(failure);
        }
      });
    } catch (RejectedExecutionException busy) {
      TASKS.remove(result);
      return null;
    }

    return result;
  }

  public static CompletableFuture<CompiledStructure> prepare(MachineDefinition definition, BlockPos origin, MachineOrientation orientation) {
    return prepare(definition, origin, orientation, Function.identity());
  }

  static <T> CompletableFuture<T> prepare(MachineDefinition definition, BlockPos origin, MachineOrientation orientation, Function<CompiledStructure, T> finish) {
    var position = origin.immutable();
    final long requestedEpoch;
    synchronized (TEMPLATES) {
      requestedEpoch = templateEpoch;
    }
    return submit(() -> {
      var key = new Key(definition, orientation);
      CompiledStructure template;
      synchronized (TEMPLATES) {
        template = TEMPLATES.get(key);
      }
      if (template == null) {
        template = new CompiledStructure(definition, BlockPos.ZERO, orientation);
        synchronized (TEMPLATES) {
          if (!Thread.currentThread().isInterrupted() && requestedEpoch == templateEpoch) {
            TEMPLATES.put(key, template);
            while (TEMPLATES.size() > 16) {
              TEMPLATES.remove(TEMPLATES.keySet().iterator().next());
            }
          }
        }
      }
      return finish.apply(template.relocate(position));
    });
  }

  public static Permit acquireSnapshot() {
    return SNAPSHOTS.tryAcquire() ? new Permit() : null;
  }

  public static final class Permit implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();

    private Permit() {
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        SNAPSHOTS.release();
      }
    }
  }

  public static void clearTemplates() {
    synchronized (TEMPLATES) {
      templateEpoch++;
      TEMPLATES.clear();
    }
  }

  public static synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    for (var task : TASKS.toArray(CompletableFuture[]::new)) {
      task.completeExceptionally(new CancellationException("Server stopped"));
    }
    clearTemplates();
  }
}
