package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternContainerRef;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import snownee.jade.api.BlockAccessor;

final class PatternCacheTooltip {
  static final String CACHE = "mmcece:pattern_cache_v2";
  static final List<ResourceType> KINDS = List.of(ResourceType.ITEM, ResourceType.FLUID, ResourceType.CHEMICAL);
  private static final int MAX_ENTRIES = 9;
  private static final Map<PatternProviderState, Preview> PREVIEWS = Collections.synchronizedMap(new WeakHashMap<>());

  private record Candidate(PatternContainerRef address, PatternResourceKey key) {
  }

  private record Row(Candidate source, CompoundTag tag) {
  }

  private static final class Preview {
    private final long layoutRevision;
    private final CompoundTag tag = new CompoundTag();
    private final List<Row> rows = new ArrayList<>(MAX_ENTRIES);
    private long revision;

    private Preview(PatternProviderState state) {
      layoutRevision = state.physicalLayoutRevision();
      revision = state.revision();
    }
  }

  private PatternCacheTooltip() {
  }

  static void capture(CompoundTag data, BlockAccessor accessor, PatternProviderBlockEntity provider) {
    var state = provider.state();
    var preview = PREVIEWS.get(state);
    if (preview == null || preview.layoutRevision != state.physicalLayoutRevision()) {
      preview = buildPreview(accessor, state);
      PREVIEWS.put(state, preview);
    } else if (preview.revision != state.revision()) {
      for (var row : preview.rows) {
        row.tag().putLong("amount", state.physicalAmount(row.source().address(), row.source().key()));
      }
      preview.revision = state.revision();
    }
    if (!preview.tag.isEmpty()) {
      data.put(CACHE, preview.tag.copy());
    }
  }

  private static Preview buildPreview(BlockAccessor accessor, PatternProviderState state) {
    var preview = new Preview(state);
    var candidates = new HashMap<ResourceType, List<Candidate>>();
    for (var kind : KINDS) {
      candidates.put(kind, new ArrayList<>(MAX_ENTRIES));
    }
    long total = 0;
    var contents = state.cachedContents().iterator();
    while (contents.hasNext()) {
      var container = contents.next();
      var physical = container.physical();
      total += physical.size();
      for (var key : physical.keySet()) {
        var group = candidates.get(key.kind());
        if (group != null && group.size() < MAX_ENTRIES) {
          group.add(new Candidate(container.address(), key));
        }
      }
    }
    if (total == 0) {
      return preview;
    }
    int shown = 0;
    for (int index = 0; index < MAX_ENTRIES && shown < MAX_ENTRIES; index++) {
      for (var kind : KINDS) {
        var group = candidates.get(kind);
        if (index >= group.size()) {
          continue;
        }
        var entry = group.get(index);
        var resource = entry.key().resource(accessor.getLevel().registryAccess());
        var row = displayRow(accessor, resource);
        row.putLong("amount", state.physicalAmount(entry.address(), entry.key()));
        preview.rows.add(new Row(entry, row));
        var rows = preview.tag.getList(kind.name(), Tag.TAG_COMPOUND);
        rows.add(row);
        preview.tag.put(kind.name(), rows);
        if (++shown == MAX_ENTRIES) {
          break;
        }
      }
    }
    preview.tag.putLong("more", total - shown);
    return preview;
  }

  private static CompoundTag displayRow(BlockAccessor accessor, NetworkPortResource resource) {
    var row = new CompoundTag();
    // Only the prototype and its rendering fields travel to an observer.
    var icon = switch (resource) {
      case NetworkPortResource.Item item -> {
        var source = item.stack();
        var display = new ItemStack(source.getItem());
        display.copyFrom(source, DataComponents.CUSTOM_MODEL_DATA, DataComponents.DYED_COLOR, DataComponents.MAP_COLOR);
        if (display.hasFoil() != source.hasFoil()) {
          display.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, source.hasFoil());
        }
        putNameIfDifferent(row, accessor, source.getHoverName(), display.getHoverName());
        yield new NetworkPortResource.Item(display);
      }
      case NetworkPortResource.Fluid fluid -> {
        var source = fluid.stack();
        var display = new FluidStack(source.getFluid(), 1);
        display.copyFrom(source, DataComponents.CUSTOM_MODEL_DATA, DataComponents.DYED_COLOR);
        putNameIfDifferent(row, accessor, source.getHoverName(), display.getHoverName());
        yield new NetworkPortResource.Fluid(display);
      }
      default -> resource;
    };
    row.put("resource", icon.save(accessor.getLevel().registryAccess()));

    return row;
  }

  private static void putNameIfDifferent(CompoundTag row, BlockAccessor accessor, Component name, Component displayName) {
    if (!name.equals(displayName)) {
      var encoded = ComponentSerialization.CODEC.encodeStart(accessor.nbtOps(), name).result()
          .filter(tag -> tag.sizeInBytes() <= 2048).orElseGet(() -> StringTag.valueOf(name.getString(128)));
      row.put("name", encoded);
    }
  }
}
