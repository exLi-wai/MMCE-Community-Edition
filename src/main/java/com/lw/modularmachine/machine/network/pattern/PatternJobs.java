package com.lw.modularmachine.machine.network.pattern;

import com.lw.modularmachine.machine.network.pattern.PatternResourceLedger.JobStatus;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal server receipt authority. Terminal proof survives unloaded
 * providers; absence of a live CPU is unknown and never synthesizes a
 * cancellation.
 */
@ApiStatus.Internal
public final class PatternJobs extends SavedData {
  private static final String NAME = "mmcece_pattern_jobs";
  private static final Factory<PatternJobs> FACTORY = new Factory<>(PatternJobs::new, PatternJobs::load);
  private static Authority authority;
  private final Map<UUID, Job> jobs = new LinkedHashMap<>();
  private CompoundTag unsupported;

  public record Location(ResourceLocation dimension, BlockPos position) {
    public Location {
      Objects.requireNonNull(dimension);
      position = Objects.requireNonNull(position).immutable();
    }
  }

  public record Job(UUID id, Location cpu, JobStatus status) {
  }

  @FunctionalInterface
  public interface Authority {
    boolean canStart(ServerLevel level, Job job);
  }

  private PatternJobs() {
  }

  public static void install(Authority value) {
    if (authority != null) {
      throw new IllegalStateException("Pattern order authority already installed");
    }
    authority = Objects.requireNonNull(value);
  }

  public static PatternJobs get(ServerLevel level) {
    ServerThreadGuard.require(level, "Pattern order state");

    return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
  }

  public static boolean canStart(ServerLevel level, UUID job) {
    ServerThreadGuard.require(level, "Pattern order start");
    if (job == null) {
      return true;
    }
    var state = get(level);
    var entry = state.job(job);

    return entry != null && entry.status() == JobStatus.ACTIVE && authority != null && authority.canStart(level, entry);
  }

  public boolean valid() {
    return unsupported == null;
  }

  public Job job(UUID id) {
    return unsupported == null ? jobs.get(id) : null;
  }

  public List<Job> known() {
    return unsupported == null ? List.copyOf(jobs.values()) : List.of();
  }

  /** Preflight before the provider owns the CPU's already-extracted input. */
  public boolean accepts(UUID id, Location location) {
    var previous = job(id);

    return valid() && (previous == null || previous.status() == JobStatus.ACTIVE && previous.cpu().equals(location));
  }

  /** No callbacks: called immediately after local receipt resolve. */
  public void accepted(UUID id, Location location) {
    if (!accepts(id, location)) {
      throw new IllegalStateException("Invalid pattern order acceptance");
    }
    if (!jobs.containsKey(id)) {
      jobs.put(id, new Job(id, location, JobStatus.ACTIVE));
      setDirty();
    }
  }

  public void finish(UUID id, boolean success) {
    var previous = job(id);
    if (previous == null || previous.status() != JobStatus.ACTIVE) {
      return;
    }
    jobs.put(id, new Job(id, previous.cpu(), success ? JobStatus.COMPLETED : JobStatus.CANCELED));
    setDirty();
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    if (unsupported != null) {
      return unsupported.copy();
    }
    tag.putInt("schema", 1);
    var list = new ListTag();
    jobs.values().forEach(job -> {
      var entry = new CompoundTag();
      entry.putUUID("id", job.id());
      entry.putString("dimension", job.cpu().dimension().toString());
      entry.putLong("position", job.cpu().position().asLong());
      entry.putString("status", job.status().name());
      list.add(entry);
    });
    tag.put("jobs", list);

    return tag;
  }

  private static PatternJobs load(CompoundTag tag, HolderLookup.Provider registries) {
    var state = new PatternJobs();
    try {
      if (!tag.contains("schema", Tag.TAG_INT)
          || tag.getInt("schema") != 1
          || !(tag.get("jobs") instanceof ListTag list)
          || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
        throw new IllegalArgumentException("Unsupported pattern order state");
      }
      for (var element : (ListTag) tag.get("jobs")) {
        var entry = (CompoundTag) element;
        if (!entry.hasUUID("id")
            || !entry.contains("position", Tag.TAG_LONG)
            || !entry.contains("dimension", Tag.TAG_STRING)
            || !entry.contains("status", Tag.TAG_STRING)) {
          throw new IllegalArgumentException("Incomplete pattern order");
        }
        var id = entry.getUUID("id");
        var location = new Location(
            ResourceLocation.parse(entry.getString("dimension")),
            BlockPos.of(entry.getLong("position")));
        if (state.jobs.put(id, new Job(id, location, JobStatus.valueOf(entry.getString("status")))) != null) {
          throw new IllegalArgumentException("Duplicate pattern order");
        }
      }
    } catch (RuntimeException invalid) {
      state.jobs.clear();
      state.unsupported = tag.copy();
    }

    return state;
  }
}
