package com.lw.modularmachine.machine.recipe.command;

import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import com.lw.modularmachine.api.recipe.RecipeCommandActions.Action;
import com.lw.modularmachine.api.recipe.RecipeCommandActions.Source;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal bounded persistence for command claims and deferred actions. */
@ApiStatus.Internal
public final class ControllerCommandState {
  public static final String NBT_KEY = "controller_command_actions";
  public static final int MAX_PENDING = 128;
  public static final int MAX_MARKS = 2048;
  private static final int RETAIN_TERMINAL = 256;
  private final Map<Key, Mark> marks = new LinkedHashMap<>();
  private final Map<UUID, Set<ResourceLocation>> byRun = new HashMap<>();
  private final Map<Key, Integer> pendingCounts = new HashMap<>();
  private final ArrayDeque<Key> terminal = new ArrayDeque<>();
  private final ArrayDeque<Pending> pending = new ArrayDeque<>();
  private Tag unsupported;
  private long claimed, rejected, skipped, failed;

  public enum Admission {
    ACCEPTED,
    DUPLICATE,
    REJECTED
  }

  private record Key(UUID run, ResourceLocation action) {
  }

  private record Mark(boolean started, long tick, boolean finished, boolean terminal) {
  }

  private record Token(Key key, Type type, long tick) {
  }

  public record Pending(
      Action action,
      UUID run,
      ResourceLocation recipe,
      int progress,
      int parallel,
      long tick,
      ResourceLocation dimension,
      BlockPos position,
      Optional<UUID> owner,
      String command) {
    public Pending {
      java.util.Objects.requireNonNull(action, "action");
      java.util.Objects.requireNonNull(run, "run");
      java.util.Objects.requireNonNull(recipe, "recipe");
      java.util.Objects.requireNonNull(dimension, "dimension");
      java.util.Objects.requireNonNull(owner, "owner");
      position = position.immutable();
      if (progress < 0
          || parallel < 1
          || tick < 0
          || command == null
          || command.isBlank()
          || command.length() > RecipeCommandActions.MAX_COMMAND_LENGTH
          || command.chars().anyMatch(value -> value < 32 || value == 127)) {
        throw new IllegalArgumentException("Invalid pending recipe command");
      }
      if (action.recipe().isPresent() && !action.recipe().get().equals(recipe)
          || action.type() == Type.STARTED && progress != 0
          || action.type() == Type.TICK && (progress == 0 || progress % action.interval() != 0)
          || action.type() == Type.FINISHED && progress == 0) {
        throw new IllegalArgumentException("Pending command does not match its lifecycle declaration");
      }
    }
  }

  public boolean valid() {
    return unsupported == null;
  }

  public boolean hasPending() {
    return valid() && !pending.isEmpty();
  }

  public boolean hasMarks() {
    return valid() && !marks.isEmpty();
  }

  public RecipeCommandActions.Status status() {
    return new RecipeCommandActions.Status(valid(), pending.size(), marks.size(), claimed, rejected, skipped, failed);
  }

  Admission enqueue(Pending request) {
    if (!valid()) {
      return Admission.REJECTED;
    }
    var key = new Key(request.run(), request.action().id());
    var previous = marks.get(key);
    var type = request.action().type();
    if (previous != null
        && (type == Type.STARTED && (previous.started() || previous.terminal())
            || type == Type.TICK && (previous.tick() >= request.tick() || previous.terminal())
            || type == Type.FINISHED && previous.finished())) {
      return Admission.DUPLICATE;
    }
    if (previous == null) {
      prune(MAX_MARKS - 1);
    }
    if (previous == null && marks.size() >= MAX_MARKS) {
      rejected = increment(rejected);
      return Admission.REJECTED;
    }
    var old = previous == null ? new Mark(false, -1, false, false) : previous;
    var next = new Mark(
        old.started() || type == Type.STARTED,
        type == Type.TICK ? request.tick() : old.tick(),
        old.finished() || type == Type.FINISHED,
        old.terminal() || type == Type.FINISHED);
    marks.put(key, next);
    byRun.computeIfAbsent(key.run(), ignored -> new LinkedHashSet<>()).add(key.action());
    if (!old.terminal() && next.terminal()) {
      terminal.addLast(key);
    }
    if (pending.size() >= MAX_PENDING) {
      rejected = increment(rejected);
      return Admission.REJECTED;
    }
    pending.addLast(request);
    pendingCounts.merge(key, 1, Integer::sum);

    return Admission.ACCEPTED;
  }

  void retire(UUID run) {
    for (var action : byRun.getOrDefault(run, Set.of())) {
      var key = new Key(run, action);
      var mark = marks.get(key);
      if (!mark.terminal()) {
        marks.put(key, new Mark(mark.started(), mark.tick(), mark.finished(), true));
        terminal.addLast(key);
      }
    }
    prune(MAX_MARKS);
  }

  Pending claim() {
    var result = pending.removeFirst();
    var key = new Key(result.run(), result.action().id());
    pendingCounts.compute(key, (ignored, count) -> count == null || count <= 1 ? null : count - 1);
    claimed = increment(claimed);
    prune(MAX_MARKS);

    return result;
  }

  void skipped() {
    skipped = increment(skipped);
  }

  void failed() {
    failed = increment(failed);
  }

  void rejected() {
    rejected = increment(rejected);
  }

  private void prune(int maximum) {
    int visits = terminal.size();
    while (visits-- > 0 && (marks.size() > maximum || terminal.size() > RETAIN_TERMINAL)) {
      var key = terminal.removeFirst();
      if (pendingCounts.containsKey(key)) {
        terminal.addLast(key);
        continue;
      }
      marks.remove(key);
      var actions = byRun.get(key.run());
      actions.remove(key.action());
      if (actions.isEmpty()) {
        byRun.remove(key.run());
      }
    }
  }

  public Tag save(boolean portable) {
    if (!valid()) {
      return unsupported.copy();
    }
    var root = new CompoundTag();
    root.putInt("schema", 1);
    var savedMarks = new ListTag();
    marks.forEach((key, mark) -> {
      var entry = new CompoundTag();
      entry.putUUID("run", key.run());
      entry.putString("action", key.action().toString());
      entry.putBoolean("started", mark.started());
      entry.putLong("tick", mark.tick());
      entry.putBoolean("finished", mark.finished());
      entry.putBoolean("terminal", mark.terminal());
      savedMarks.add(entry);
    });
    root.put("marks", savedMarks);
    var savedPending = new ListTag();
    if (!portable) {
      pending.forEach(request -> savedPending.add(savePending(request)));
    }
    root.put("pending", savedPending);
    root.putLong("claimed", claimed);
    root.putLong("rejected", rejected);
    root.putLong("skipped", skipped);
    root.putLong("failed", failed);

    return root;
  }

  public static ControllerCommandState load(Tag value) {
    var state = new ControllerCommandState();
    if (value == null) {
      return state;
    }
    try {
      if (!(value instanceof CompoundTag root) || !root.contains("schema", Tag.TAG_INT) || root.getInt("schema") != 1) {
        throw new IllegalArgumentException("Unsupported controller command state");
      }
      var savedMarks = list(root, "marks", MAX_MARKS);
      for (var tag : savedMarks) {
        var entry = (CompoundTag) tag;
        var key = new Key(uuid(entry, "run"), id(entry, "action"));
        long tick = number(entry, "tick");
        if (tick < -1) {
          throw new IllegalArgumentException("Invalid command tick marker");
        }
        var mark = new Mark(bool(entry, "started"), tick, bool(entry, "finished"), bool(entry, "terminal"));
        if (mark.finished() && !mark.terminal() || state.marks.putIfAbsent(key, mark) != null) {
          throw new IllegalArgumentException("Invalid or duplicate command marker");
        }
        state.byRun.computeIfAbsent(key.run(), ignored -> new LinkedHashSet<>()).add(key.action());
        if (mark.terminal()) {
          state.terminal.addLast(key);
        }
      }
      var tokens = new HashSet<Token>();
      for (var tag : list(root, "pending", MAX_PENDING)) {
        var request = loadPending((CompoundTag) tag);
        var key = new Key(request.run(), request.action().id());
        var mark = state.marks.get(key);
        if (mark == null
            || request.action().type() == Type.STARTED && !mark.started()
            || request.action().type() == Type.TICK && mark.tick() < request.tick()
            || request.action().type() == Type.FINISHED && !mark.finished()
            || !tokens.add(new Token(key, request.action().type(), request.tick()))) {
          throw new IllegalArgumentException("Unmarked or duplicate pending command");
        }
        state.pending.addLast(request);
        state.pendingCounts.merge(key, 1, Integer::sum);
      }
      state.claimed = counter(root, "claimed");
      state.rejected = counter(root, "rejected");
      state.skipped = counter(root, "skipped");
      state.failed = counter(root, "failed");
    } catch (RuntimeException invalid) {
      state = new ControllerCommandState();
      state.unsupported = value.copy();
    }

    return state;
  }

  /** Items cannot restore a world command outbox, including forged item data. */
  public static void sanitizeItemData(CompoundTag root) {
    var value = root.get(NBT_KEY);
    if (value != null) {
      var state = load(value);
      if (state.valid()) {
        root.put(NBT_KEY, state.save(true));
      } else {
        // An explicit invalid header prevents vanilla's recursive NBT merge from
        // completing a partial forged outbox with the new BE's default fields.
        var disabled = new CompoundTag();
        disabled.putInt("schema", -1);
        disabled.put("unsupported", value.copy());
        root.put(NBT_KEY, disabled);
      }
    }
  }

  private static CompoundTag savePending(Pending request) {
    var root = new CompoundTag();
    var action = request.action();
    root.putString("id", action.id().toString());
    root.putString("machine", action.machine().toString());
    action.recipe().ifPresent(recipe -> root.putString("filter_recipe", recipe.toString()));
    root.putString("type", action.type().name());
    root.putInt("interval", action.interval());
    root.putString("template", action.command());
    root.putString("source", action.source().name());
    root.putInt("permission", action.permissionLevel());
    root.putUUID("run", request.run());
    root.putString("recipe", request.recipe().toString());
    root.putInt("progress", request.progress());
    root.putInt("parallel", request.parallel());
    root.putLong("tick", request.tick());
    root.putString("dimension", request.dimension().toString());
    root.putInt("x", request.position().getX());
    root.putInt("y", request.position().getY());
    root.putInt("z", request.position().getZ());
    request.owner().ifPresent(owner -> root.putUUID("owner", owner));
    root.putString("command", request.command());

    return root;
  }

  private static Pending loadPending(CompoundTag root) {
    var action = new Action(
        id(root, "id"),
        id(root, "machine"),
        root.contains("filter_recipe") ? Optional.of(id(root, "filter_recipe")) : Optional.empty(),
        Type.valueOf(text(root, "type")),
        integer(root, "interval"),
        text(root, "template"),
        Source.valueOf(text(root, "source")),
        integer(root, "permission"));

    return new Pending(
        action,
        uuid(root, "run"),
        id(root, "recipe"),
        integer(root, "progress"),
        integer(root, "parallel"),
        number(root, "tick"),
        id(root, "dimension"),
        new BlockPos(integer(root, "x"), integer(root, "y"), integer(root, "z")),
        root.contains("owner") ? Optional.of(uuid(root, "owner")) : Optional.empty(),
        text(root, "command"));
  }

  private static ListTag list(CompoundTag root, String key, int maximum) {
    if (!(root.get(key) instanceof ListTag list)
        || list.size() > maximum
        || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid command state list: " + key);
    }

    return list;
  }

  private static String text(CompoundTag root, String key) {
    if (!root.contains(key, Tag.TAG_STRING) || root.getString(key).length() > RecipeCommandActions.MAX_COMMAND_LENGTH) {
      throw new IllegalArgumentException("Invalid command state string: " + key);
    }

    return root.getString(key);
  }

  private static ResourceLocation id(CompoundTag root, String key) {
    String value = text(root, key);
    if (value.length() > 256) {
      throw new IllegalArgumentException("Command resource ID is too long");
    }

    return ResourceLocation.parse(value);
  }

  private static UUID uuid(CompoundTag root, String key) {
    if (!root.hasUUID(key)) {
      throw new IllegalArgumentException("Invalid command state UUID: " + key);
    }

    return root.getUUID(key);
  }

  private static int integer(CompoundTag root, String key) {
    if (!root.contains(key, Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid command state integer: " + key);
    }

    return root.getInt(key);
  }

  private static long number(CompoundTag root, String key) {
    if (!root.contains(key, Tag.TAG_LONG)) {
      throw new IllegalArgumentException("Invalid command state long: " + key);
    }

    return root.getLong(key);
  }

  private static long counter(CompoundTag root, String key) {
    long value = number(root, key);
    if (value < 0) {
      throw new IllegalArgumentException("Invalid command state counter");
    }

    return value;
  }

  private static boolean bool(CompoundTag root, String key) {
    if (!root.contains(key, Tag.TAG_BYTE) || root.getByte(key) < 0 || root.getByte(key) > 1) {
      throw new IllegalArgumentException("Invalid command state boolean: " + key);
    }

    return root.getBoolean(key);
  }

  private static long increment(long value) {
    return value == Long.MAX_VALUE ? value : value + 1;
  }
}
