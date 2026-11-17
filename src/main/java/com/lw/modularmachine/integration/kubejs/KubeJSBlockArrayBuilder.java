package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.structure.StructureBlockCheck;
import com.lw.modularmachine.machine.structure.StructureScriptChecks;
import dev.latvian.mods.rhino.Wrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.structure.DynamicPatternDefinition;

/**
 * Declarative block arrays; attached builders share their owner's sealing
 * boundary.
 */
@ApiStatus.Internal
public final class KubeJSBlockArrayBuilder {
  private final Thread owner = Thread.currentThread();
  private final KubeJSMachineBuilder machine;
  private final LinkedHashMap<BlockPos, JsonObject> parts = new LinkedHashMap<>();
  private final Map<BlockPos, CompoundTag> required = new LinkedHashMap<>();
  private final Map<BlockPos, CompoundTag> preview = new LinkedHashMap<>();
  private final Map<BlockPos, StructureBlockCheck> checkers = new LinkedHashMap<>();
  private List<BlockPos> last = List.of();

  KubeJSBlockArrayBuilder(KubeJSMachineBuilder machine, JsonArray initial) {
    this.machine = machine;
    for (var value : initial) {
      var part = value.getAsJsonObject().deepCopy();
      var pos = part.getAsJsonArray("offset");
      parts.put(new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()), part);
    }
  }

  public static KubeJSBlockArrayBuilder newBuilder() {
    return new KubeJSBlockArrayBuilder(null, new JsonArray());
  }

  public static KubeJSBlockArrayBuilder newBuilder(KubeJSBlockArrayBuilder source) {
    source.mutable();
    var copy = new KubeJSBlockArrayBuilder(null, new JsonArray());
    source.parts.forEach((pos, part) -> copy.parts.put(pos, part.deepCopy()));
    source.required.forEach((pos, data) -> copy.required.put(pos, data.copy()));
    source.preview.forEach((pos, data) -> copy.preview.put(pos, data.copy()));
    copy.checkers.putAll(source.checkers);
    copy.last = source.last;
    return copy;
  }

  public KubeJSBlockArrayBuilder addBlock(int x, int y, int z, Object... blocks) {
    mutable();
    var position = new BlockPos(x, y, z);
    if (position.equals(BlockPos.ZERO)) {
      throw new IllegalArgumentException("The controller origin cannot be a structure part");
    }
    if (blocks.length == 0) {
      throw new IllegalArgumentException("A structure part requires at least one block");
    }
    if (!parts.containsKey(position) && parts.size() >= DynamicPatternDefinition.MAX_EXPANDED_PARTS) {
      throw new IllegalArgumentException("Structure block array exceeds the part budget");
    }
    var names = new JsonArray();
    JsonObject properties = null;
    for (var value : blocks) {
      var block = block(value);
      names.add(block.get("block").getAsString());
      var candidate = block.getAsJsonObject("properties");
      if (properties == null) {
        properties = candidate;
      } else if (!properties.equals(candidate)) {
        throw new IllegalArgumentException("Block alternatives must declare the same property requirements");
      }
    }
    var part = new JsonObject();
    var offset = new JsonArray();
    offset.add(x);
    offset.add(y);
    offset.add(z);
    part.add("offset", offset);
    // Replacing a declaration keeps references from existing modifier groups valid.
    part.addProperty("part_id", parts.containsKey(position) ? partId(position) : "script_" + x + "_" + y + "_" + z);
    part.add("block", names.size() == 1 ? names.get(0) : names);
    boolean smart = names.asList().stream().allMatch(name -> {
      var id = ResourceLocation.tryParse(name.getAsString());
      return id != null && BuiltInRegistries.BLOCK.get(id) instanceof SmartInterfaceBlock;
    });
    if (smart) {
      part.addProperty("interface", "*");
    }
    if (properties != null && !properties.isEmpty()) {
      part.add("properties", properties);
    }
    parts.put(position, part);
    required.remove(position);
    preview.remove(position);
    checkers.remove(position);
    last = List.of(position);
    return this;
  }

  public KubeJSBlockArrayBuilder addBlock(int[] x, int[] y, int[] z, Object... blocks) {
    mutable();
    if (x.length == 0
        || y.length == 0
        || z.length == 0
        || (long) x.length * y.length > DynamicPatternDefinition.MAX_EXPANDED_PARTS / z.length) {
      throw new IllegalArgumentException("Structure coordinate product is empty or exceeds the structure part budget");
    }
    var added = new ArrayList<BlockPos>();
    for (int xValue : x) {
      for (int yValue : y) {
        for (int zValue : z) {
          addBlock(xValue, yValue, zValue, blocks);
          added.addAll(last);
        }
      }
    }
    last = List.copyOf(added);
    return this;
  }

  public KubeJSBlockArrayBuilder addBlock(int[] x, int[] y, int[] z, CompoundTag nbt, CompoundTag previewNbt, StructureBlockCheck checker, Object... blocks) {
    addBlock(x, y, z, blocks);
    if (nbt != null) {
      setNBT(nbt);
    }
    if (previewNbt != null) {
      setPreviewNBT(previewNbt);
    }
    if (checker != null) {
      setBlockChecker(checker);
    }
    return this;
  }

  public KubeJSBlockArrayBuilder addBlock(int[] x, int[] y, int[] z, CompoundTag nbt, CompoundTag previewNbt, Object... blocks) {
    return addBlock(x, y, z, nbt, previewNbt, null, blocks);
  }

  public KubeJSBlockArrayBuilder addBlock(int[] x, int[] y, int[] z, StructureBlockCheck checker, Object... blocks) {
    return addBlock(x, y, z, null, null, checker, blocks);
  }

  public KubeJSBlockArrayBuilder setNBT(CompoundTag value) {
    mutableLast();
    last.forEach(pos -> required.put(pos, Objects.requireNonNull(value, "NBT").copy()));
    last.forEach(pos -> preview.putIfAbsent(pos, value.copy()));
    return this;
  }

  public KubeJSBlockArrayBuilder setPreviewNBT(CompoundTag value) {
    mutableLast();
    last.forEach(pos -> preview.put(pos, Objects.requireNonNull(value, "preview NBT").copy()));
    return this;
  }

  public KubeJSBlockArrayBuilder setBlockChecker(StructureBlockCheck value) {
    mutableLast();
    last.forEach(pos -> checkers.put(pos, Objects.requireNonNull(value, "block checker")));
    return this;
  }

  public KubeJSBlockArrayBuilder setTag(String tag) {
    mutableLast();
    ResourceSelector.require(tag);
    last.forEach(pos -> parts.get(pos).addProperty("selector", tag));
    return this;
  }

  public KubeJSBlockArrayBuilder getBlockArray() {
    return this;
  }

  public KubeJSBlockArrayBuilder build() {
    mutable();
    return this;
  }

  void mutable() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("Block array used outside its creating thread");
    }
    if (machine != null) {
      machine.mutable();
    }
  }

  private void mutableLast() {
    mutable();
    if (last.isEmpty()) {
      throw new IllegalStateException("A block must be added before changing its declaration");
    }
  }

  JsonArray capture(KubeJSMachineBuilder.Session session) {
    mutable();
    var result = new JsonArray();
    for (var entry : parts.entrySet()) {
      var pos = entry.getKey();
      var part = entry.getValue().deepCopy();
      if (required.containsKey(pos) || preview.containsKey(pos) || checkers.containsKey(pos)) {
        if (session == null) {
          throw new IllegalStateException("NBT and checker block arrays must be captured by a machine");
        }
        var condition = new StructureScriptChecks.Condition(required.get(pos), preview.get(pos), checkers.get(pos));
        part.addProperty("script_condition", session.condition(condition));
      }
      result.add(part);
    }
    return result;
  }

  String partId(BlockPos offset) {
    mutable();
    var part = parts.get(offset);
    if (part == null) {
      throw new IllegalArgumentException("Structure modifier must target a declared fixed part: " + offset);
    }
    if (part.has("part_id")) {
      return part.get("part_id").getAsString();
    }

    return "offset:" + offset.getX() + "," + offset.getY() + "," + offset.getZ();
  }

  static JsonObject block(Object input) {
    while (input instanceof Wrapper wrapper) {
      input = wrapper.unwrap();
    }
    if (input instanceof ItemStack stack) {
      if (!(stack.getItem() instanceof BlockItem item)) {
        throw new IllegalArgumentException("Structure items must place a block");
      }
      input = item.getBlock();
    }
    var result = new JsonObject();
    var properties = new JsonObject();
    if (input instanceof BlockState state) {
      result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
      state.getValues()
          .forEach((property, value) -> properties.addProperty(property.getName(), propertyName(property, value)));
    } else if (input instanceof Block block) {
      result.addProperty("block", BuiltInRegistries.BLOCK.getKey(block).toString());
    } else if (input instanceof CharSequence text) {
      String literal = text.toString();
      for (var kind : PortKind.values()) {
        if (kind.name().equalsIgnoreCase(literal) || kind.path().equalsIgnoreCase(literal)) {
          result.addProperty("block", kind.path());
          result.add("properties", properties);
          return result;
        }
      }
      int bracket = literal.indexOf('[');
      var id = ResourceLocation.parse(bracket < 0 ? literal : literal.substring(0, bracket));
      result.addProperty("block", id.toString());
      if (bracket >= 0) {
        if (!literal.endsWith("]")) {
          throw new IllegalArgumentException("Invalid block state literal " + literal);
        }
        for (var property : literal.substring(bracket + 1, literal.length() - 1).split(",")) {
          String[] pair = property.split("=", -1);
          if (pair.length != 2 || pair[0].isEmpty() || pair[1].isEmpty() || properties.has(pair[0])) {
            throw new IllegalArgumentException("Invalid block state property " + property);
          }
          properties.addProperty(pair[0], pair[1]);
        }
      }
    } else {
      throw new IllegalArgumentException("Expected block ID, BlockState, Block or block item");
    }
    result.add("properties", properties);
    return result;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String propertyName(Property property, Comparable value) {
    return property.getName(value);
  }
}
