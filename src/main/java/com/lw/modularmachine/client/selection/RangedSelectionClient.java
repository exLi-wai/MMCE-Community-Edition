package com.lw.modularmachine.client.selection;

import com.google.gson.JsonObject;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.selection.PartOverrides;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEditor;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Internal client design session. Drafts confer no server-side write authority.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class RangedSelectionClient {
  public enum Target {
    MAIN("main_structure"),
    DYNAMIC("dynamic_structure"),
    START("start"),
    END("end"),
    PART("part"),
    CONTROLLER("controller");

    private final String label;
    Target(String label) {
      this.label = label;
    }
    public Component title() {
      return text(label);
    }
  }

  private static final class Session {
    final ClientLevel level;
    final RangedSelectionCapture capture = new RangedSelectionCapture();
    final Set<Long> dirtySections = ConcurrentHashMap.newKeySet();
    final Set<Long> dirtyChunks = ConcurrentHashMap.newKeySet();
    volatile List<Box> watched = List.of();
    Design design = Design.defaults();
    long revision;
    int selected, nextKey = 2;
    RangedConstructToolScreen editor;
    Target picking;
    BlockPos first;
    PartEditor part;
    Component error = Component.empty();

    Session(ClientLevel level) {
      this.level = level;
      capture.select(level, 0, design);
    }
  }

  private static volatile Session session;
  private RangedSelectionClient() {
  }

  static Component text(String key, Object... args) {
    return Component.translatable("gui.mmcece.selection." + key, args);
  }

  private static Session useLevel() {
    var level = Minecraft.getInstance().level;
    if (level == null) {
      reset();
      return null;
    }
    if (session == null || session.level != level) {
      reset();
      session = new Session(level);
    }
    return session;
  }

  public static boolean valid() {
    var minecraft = Minecraft.getInstance();
    return session != null
        && session.level == minecraft.level
        && minecraft.player != null
        && minecraft.player.getMainHandItem().getItem() instanceof RangedConstructToolItem;
  }

  public static void use(@Nullable BlockPos position) {
    if (!Screen.hasControlDown()) {
      open();
    } else if (position != null) {
      pick(position);
    }
  }

  public static void open() {
    var current = useLevel();
    if (current == null || !valid()) {
      return;
    }
    current.picking = null;
    current.first = null;
    if (current.editor == null) {
      current.editor = new RangedConstructToolScreen();
    }
    if (Minecraft.getInstance().screen != current.editor) {
      Minecraft.getInstance().setScreen(current.editor);
    }
  }

  static Design design() {
    return session.design;
  }
  static int selectedSegment() {
    return session.selected;
  }
  static String selectedKey() {
    return design().keys().get(session.selected);
  }
  static PartEditor partEditor() {
    return session.part;
  }

  static Component segmentTitle(String key) {
    int index = design().keys().indexOf(key);
    if (index < 0) {
      return Target.DYNAMIC.title();
    }
    String name = design().segments().get(index).settings().id();
    return name.isEmpty() ? text("segment_number", index + 1) : Component.literal(name);
  }

  static void selectSegment(String key) {
    int index = design().keys().indexOf(key);
    if (index >= 0) {
      session.selected = index;
    }
  }

  static boolean update(UnaryOperator<Design> change, boolean capture, boolean newSelection) {
    if (!valid()) {
      return false;
    }
    try {
      var current = session;
      var next = change.apply(current.design);
      if (capture) {
        current.capture.select(current.level, current.revision + 1, next);
      }
      current.design = next;
      current.revision++;
      current.selected = Math.min(current.selected, next.segments().size() - 1);
      current.watched = boxes(next);
      current.error = Component.empty();
      if (newSelection) {
        current.part = null;
        current.dirtySections.clear();
        current.dirtyChunks.clear();
        if (current.editor != null) {
          current.editor.selectionChanged();
        }
      }
      return true;
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      error(invalid.getMessage());
      return false;
    }
  }

  static void error(String message) {
    if (session != null) {
      session.error = text("invalid_design", message == null ? "" : message);
    }
  }

  static boolean setLength(String key, int value) {
    int index = design().keys().indexOf(key);
    if (index < 0) {
      return false;
    }
    return update(draft -> {
      var old = draft.segments().get(index).settings();
      return draft.withSettings(
          index,
          new Settings(
              old.id(),
              1,
              value,
              old.direction(),
              old.gap(),
              old.customStep(),
              old.step(),
              old.customOrigin(),
              old.origin(),
              old.sampleLength(),
              old.selector(),
              old.interfaceType()));
    }, false, false);
  }

  static boolean addSegment() {
    String key = "selection_" + session.nextKey++;
    boolean added = update(draft -> {
      var segments = new ArrayList<>(draft.segments());
      var keys = new ArrayList<>(draft.keys());
      segments.add(Design.defaults().segments().getFirst());
      keys.add(key);
      return draft.withSegments(segments, keys);
    }, true, false);
    if (added) {
      selectSegment(key);
    }
    return added;
  }

  static boolean removeSegment() {
    return update(draft -> {
      var segments = new ArrayList<>(draft.segments());
      var keys = new ArrayList<>(draft.keys());
      segments.remove(session.selected);
      keys.remove(session.selected);
      if (segments.isEmpty()) {
        segments.add(Design.defaults().segments().getFirst());
        keys.add("selection_" + session.nextKey++);
      }
      return draft.withSegments(segments, keys).withoutPartEdits();
    }, true, true);
  }

  static boolean clear(Target target) {
    return update(draft -> replaceBox(draft, target, null).withoutPartEdits(), true, true);
  }

  private static Design replaceBox(Design draft, Target target, @Nullable Box box) {
    if (target == Target.MAIN) {
      return draft.withMain(box);
    }
    if (target == Target.CONTROLLER) {
      return draft.withBasis(null, MachineOrientation.IDENTITY);
    }
    var segments = new ArrayList<>(draft.segments());
    var old = segments.get(session.selected);
    segments.set(
        session.selected,
        new Segment(
            old.settings(),
            target == Target.DYNAMIC ? box : old.body(),
            target == Target.START ? box : old.start(),
            target == Target.END ? box : old.end()));
    return draft.withSegments(segments, draft.keys());
  }

  public static void beginPick(Target target) {
    if (!valid()) {
      return;
    }
    session.picking = target;
    session.first = null;
    session.error = Component.empty();
    Minecraft.getInstance().setScreen(null);
    boolean single = target == Target.PART || target == Target.CONTROLLER;
    Minecraft.getInstance().player
        .displayClientMessage(text(single ? "pick_single" : "pick_first", target.title()), true);
  }

  public static void pick(BlockPos position) {
    if (!valid() || session.picking == null) {
      return;
    }
    var current = session;
    if (current.picking == Target.PART) {
      pickPart(position);
      return;
    }
    if (current.picking == Target.CONTROLLER) {
      var state = current.level.getBlockState(position);
      MachineOrientation orientation;
      if (state.getBlock() instanceof MachineControllerBlock) {
        orientation = MachineControllerBlock.orientationOf(state);
      } else if (state.getBlock() instanceof FactoryControllerBlock) {
        orientation = FactoryControllerBlock.orientationOf(state);
      } else {
        current.error = text("not_controller");
        open();
        return;
      }
      update(draft -> draft.withBasis(position, orientation), true, true);
      open();
      return;
    }
    if (current.first == null) {
      current.first = position.immutable();
      Minecraft.getInstance().player.displayClientMessage(text("pick_second", current.picking.title()), true);
      return;
    }
    var box = new Box(current.first, position);
    update(draft -> replaceBox(draft, current.picking, box).withoutPartEdits(), true, true);
    open();
  }

  private static void pickPart(BlockPos position) {
    var current = session;
    var state = current.level.getBlockState(position);
    if (current.watched.stream().noneMatch(box -> contains(box, position))
        || state.getBlock() instanceof MachineControllerBlock
        || state.getBlock() instanceof FactoryControllerBlock
        || state.isAir() && !current.design.includeAir()) {
      current.error = text("invalid_part");
      open();
      return;
    }
    var edit = current.design.partEdits().getOrDefault(position, PartEdit.inherit());
    String properties = state.getProperties().stream().map(property -> assignment(state, property))
        .collect(Collectors.joining(";"));
    current.part = new PartEditor(
        position,
        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
        properties,
        edit);
    open();
    current.editor.showPartEditor();
  }

  private static <T extends Comparable<T>> String assignment(BlockState state, Property<T> property) {
    return property.getName() + "=" + property.getName(state.getValue(property));
  }

  static boolean savePart(@Nullable PartEdit edit) {
    if (session.part == null) {
      return false;
    }
    try {
      PartOverrides.apply(new JsonObject(), edit);
    } catch (IllegalArgumentException invalid) {
      error(invalid.getMessage());
      return false;
    }
    var previous = session.part;
    boolean saved = update(draft -> draft.withPartEdit(previous.position(), edit), true, false);
    if (saved) {
      session.part = new PartEditor(
          previous.position(),
          previous.observedBlock(),
          previous.observedProperties(),
          edit == null ? PartEdit.inherit() : edit);
    }
    return saved;
  }

  static RangedSelectionCapture capture() {
    return valid() ? session.capture : null;
  }

  static Component status() {
    if (!valid()) {
      return Component.empty();
    }
    var error = error();
    if (!error.getString().isEmpty()) {
      return error;
    }
    if (session.capture.waitingForChunks()) {
      return text("waiting_chunks");
    }
    if (session.capture.building()) {
      return text("capturing");
    }
    return text("select_hint");
  }

  static Component error() {
    if (!valid()) {
      return Component.empty();
    }
    return session.error.getString().isEmpty() ? session.capture.error() : session.error;
  }

  static void clearInteractionError() {
    if (valid()) {
      session.error = Component.empty();
    }
  }

  static int advance(int budget, long deadline) {
    if (!valid()) {
      return budget;
    }
    var current = session;
    int remaining = budget;
    var chunks = current.dirtyChunks.iterator();
    for (int work = 0; chunks.hasNext() && work < 32 && remaining > 0 && System.nanoTime() < deadline; work++) {
      long chunk = chunks.next();
      chunks.remove();
      current.capture.chunkChanged(current.level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
      remaining--;
    }
    var sections = current.dirtySections.iterator();
    for (int work = 0; sections.hasNext() && work < 64 && remaining > 0 && System.nanoTime() < deadline; work++) {
      long section = sections.next();
      sections.remove();
      current.capture
          .sectionChanged(current.level, SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
      remaining--;
    }
    return remaining - current.capture.advance(remaining, deadline);
  }

  private static List<Box> boxes(Design design) {
    var boxes = new ArrayList<Box>();
    if (design.main() != null) {
      boxes.add(design.main());
    }
    for (var segment : design.segments()) {
      if (segment.body() != null) {
        boxes.add(segment.body());
      }
      if (segment.start() != null) {
        boxes.add(segment.start());
      }
      if (segment.end() != null) {
        boxes.add(segment.end());
      }
    }
    return List.copyOf(boxes);
  }

  private static boolean contains(Box box, BlockPos position) {
    return position.getX() >= Math.min(box.first().getX(), box.second().getX())
        && position.getX() <= Math.max(box.first().getX(), box.second().getX())
        && position.getY() >= Math.min(box.first().getY(), box.second().getY())
        && position.getY() <= Math.max(box.first().getY(), box.second().getY())
        && position.getZ() >= Math.min(box.first().getZ(), box.second().getZ())
        && position.getZ() <= Math.max(box.first().getZ(), box.second().getZ());
  }

  /**
   * Render invalidations may be off-thread: only immutable coordinates are read.
   */
  public static void sectionChanged(int x, int y, int z) {
    var current = session;
    if (current != null && current.watched.stream().anyMatch(box -> intersects(box, x, y, z))) {
      current.dirtySections.add(SectionPos.asLong(x, y, z));
    }
  }

  private static boolean intersects(Box box, int x, int y, int z) {
    return intersectsChunk(box, x, z)
        && y >= (Math.min(box.first().getY(), box.second().getY()) >> 4)
        && y <= (Math.max(box.first().getY(), box.second().getY()) >> 4);
  }

  private static boolean intersectsChunk(Box box, int x, int z) {
    return x >= (Math.min(box.first().getX(), box.second().getX()) >> 4)
        && x <= (Math.max(box.first().getX(), box.second().getX()) >> 4)
        && z >= (Math.min(box.first().getZ(), box.second().getZ()) >> 4)
        && z <= (Math.max(box.first().getZ(), box.second().getZ()) >> 4);
  }

  private static void chunkChanged(ChunkEvent event) {
    var current = session;
    if (current == null || event.getLevel() != current.level) {
      return;
    }
    var pos = event.getChunk().getPos();
    if (current.watched.stream().anyMatch(box -> intersectsChunk(box, pos.x, pos.z))) {
      current.dirtyChunks.add(pos.toLong());
    }
  }

  @SubscribeEvent
  public static void chunkLoaded(ChunkEvent.Load event) {
    chunkChanged(event);
  }
  @SubscribeEvent
  public static void chunkUnloaded(ChunkEvent.Unload event) {
    chunkChanged(event);
  }

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    var current = session;
    if (current != null && current.level != Minecraft.getInstance().level) {
      reset();
    } else if (current != null && !valid()) {
      current.picking = null;
      current.first = null;
    }
  }

  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if (valid() && Minecraft.getInstance().screen == null) {
      SelectionBoxRenderer.render(event, session.design, session.selected, session.first);
    }
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    reset();
  }

  @SubscribeEvent
  public static void unloaded(LevelEvent.Unload event) {
    if (session != null && session.level == event.getLevel()) {
      reset();
    }
  }

  private static void reset() {
    var previous = session;
    session = null;
    if (previous != null) {
      previous.capture.reset();
      previous.dirtySections.clear();
      previous.dirtyChunks.clear();
    }
  }
}
