package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
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
  private static final int MAX_TEMPLATES = 16;
  private static final int MAX_TEMPLATE_PARTS = 1_048_576;
  private static int templateParts;
  private static final LinkedHashMap<Key, CompiledStructure> TEMPLATES = new LinkedHashMap<>(16, .75F, true);
  private static final Map<Key, CompletableFuture<CompiledStructure>> PREPARING = new HashMap<>();

  private AsyncStructureWork() {
  }

  private record Key(MachineDefinition definition, Map<String, Integer> lengths, MachineOrientation orientation) {
    Key {
      lengths = Map.copyOf(lengths);
    }

    @Override
    public int hashCode() {
      return 31 * (31 * System.identityHashCode(definition) + lengths.hashCode()) + orientation.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && key.definition == definition
          && key.orientation == orientation
          && key.lengths.equals(lengths);
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
    return prepare(definition, definition.dynamicLengths(), origin, orientation, finish);
  }

  public static CompletableFuture<CompiledStructure> prepare(MachineDefinition definition, Map<String, Integer> lengths, BlockPos origin, MachineOrientation orientation) {
    return prepare(definition, lengths, origin, orientation, Function.identity());
  }

  static <T> CompletableFuture<T> prepare(MachineDefinition definition, Map<String, Integer> lengths, BlockPos origin, MachineOrientation orientation, Function<CompiledStructure, T> finish) {
    var position = origin.immutable();
    var key = new Key(definition.sourceDefinition(), lengths, orientation);
    final long requestedEpoch;
    synchronized (TEMPLATES) {
      requestedEpoch = templateEpoch;
    }
    return submit(() -> finish.apply(template(key, requestedEpoch).relocate(position)));
  }

  private static CompiledStructure compile(Key key) {
    var definition = key.definition.resolveDynamic(key.lengths);
    return new CompiledStructure(definition, BlockPos.ZERO, key.orientation);
  }

  private static CompiledStructure template(Key key, long requestedEpoch) {
    CompletableFuture<CompiledStructure> pending = null;
    boolean owner = false;
    synchronized (TEMPLATES) {
      if (requestedEpoch == templateEpoch) {
        var cached = TEMPLATES.get(key);
        if (cached != null) {
          return cached;
        }
        pending = PREPARING.get(key);
        if (pending == null) {
          pending = new CompletableFuture<>();
          PREPARING.put(key, pending);
          owner = true;
        }
      }
    }
    // An old queued task may finish for its caller, but cannot repopulate a new
    // epoch.
    if (pending == null) {
      return compile(key);
    }
    if (!owner) {
      return pending.join();
    }
    try {
      var compiled = compile(key);
      synchronized (TEMPLATES) {
        int parts = compiled.definition.parts().size();
        if (!Thread.currentThread().isInterrupted() && requestedEpoch == templateEpoch && parts <= MAX_TEMPLATE_PARTS) {
          TEMPLATES.put(key, compiled);
          templateParts += parts;
          while (TEMPLATES.size() > MAX_TEMPLATES || templateParts > MAX_TEMPLATE_PARTS) {
            templateParts -= TEMPLATES.remove(TEMPLATES.keySet().iterator().next()).definition.parts().size();
          }
        }
        PREPARING.remove(key, pending);
      }
      pending.complete(compiled);
      return compiled;
    } catch (RuntimeException | Error failure) {
      synchronized (TEMPLATES) {
        PREPARING.remove(key, pending);
      }
      pending.completeExceptionally(failure);
      throw failure;
    }
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
      PREPARING.clear();
      templateParts = 0;
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
