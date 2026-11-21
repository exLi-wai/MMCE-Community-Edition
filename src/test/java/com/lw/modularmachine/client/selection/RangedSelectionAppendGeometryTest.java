package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.machine.selection.SelectionGeometry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RangedSelectionAppendGeometryTest {
  private static final Box BODY = new Box(new BlockPos(102, 70, -7), new BlockPos(101, 71, -4));

  @Test
  void retainedMainSuppliesAnAnchorWithoutConsumingCaptureBudget() {
    var main = new Box(new BlockPos(61, 63, -3), new BlockPos(125, 129, -66));
    var append = append(settings(false, BlockPos.ZERO)).withMain(main);

    assertNull(append.captureMain());
    assertEquals(new BlockPos(61, 63, -66), append.appendOrigin());
    assertThrows(IllegalArgumentException.class, () -> append.withOptions("new", false, "", false));
  }

  @Test
  void appendingNewBoxesKeepsTheOriginOfTheSavedSelection() {
    var main = new Box(new BlockPos(111, 74, 1), new BlockPos(110, 73, 0));
    var head = new Box(new BlockPos(114, 69, 3), new BlockPos(113, 68, 2));
    var tail = new Box(new BlockPos(111, 75, -9), new BlockPos(110, 74, -10));
    var create = Design.defaults().withMain(main)
        .withSegments(List.of(new Segment(settings(false, BlockPos.ZERO), BODY, head, tail)), List.of("first"));
    var origins = new RangedSelectionClient.ExportOrigins();
    origins.selectMain(main);
    origins.remember("target_1", create);
    var west = new Box(new BlockPos(98, 70, -7), new BlockPos(96, 71, -4));
    var edited = create
        .withSegments(List.of(new Segment(settings(false, BlockPos.ZERO), west, null, null)), List.of("new"));
    var append = edited.withOptions("", true, "TARGET_1.json", false);

    assertEquals(new BlockPos(101, 68, -10), create.previewOrigin());
    assertEquals(create.previewOrigin(), origins.previewOrigin(append));
    assertNull(append.captureMain());
    assertEquals(
        new BlockPos(-5, 2, 3),
        SelectionGeometry.bounds(west, origins.previewOrigin(append), append.orientation()).min());
    var other = append.withOptions("", true, "target", false);
    assertEquals(main.minCorner(), origins.previewOrigin(other));
    origins.selectMain(null);
    origins.selectMain(main);
    origins.remember("target_1", create.withMain(new Box(main.first(), main.second())));
    assertEquals(main.minCorner(), origins.previewOrigin(append));
  }

  @Test
  void manualStartInANewMachineLeavesAutomaticSegmentsInPlace() {
    var first = new Box(new BlockPos(100, 70, 0), new BlockPos(100, 70, 0));
    var second = new Box(new BlockPos(110, 70, 0), new BlockPos(110, 70, 0));
    var automatic = settings(false, BlockPos.ZERO);
    var create = Design.defaults().withSegments(
        List.of(new Segment(automatic, first, null, null), new Segment(automatic, second, null, null)),
        List.of("first", "second"));
    var edited = create.withSettings(0, settings(true, new BlockPos(5, 0, 0)));

    assertEquals(first.minCorner(), create.previewOrigin());
    assertEquals(create.previewOrigin(), edited.previewOrigin());
    assertEquals(
        new BlockPos(10, 0, 0),
        SelectionGeometry.bounds(second, edited.previewOrigin(), edited.orientation()).min());
  }

  @Test
  void controllerOutsideAllBoxesSetsTheLocalFrame() {
    var controller = new BlockPos(90, 69, -10);
    var orientation = MachineOrientation.of(Direction.EAST, 0);
    var append = append(settings(false, BlockPos.ZERO)).withBasis(controller, orientation);

    assertNull(append.captureMain());
    assertEquals(controller, append.appendOrigin());
    assertEquals(new BlockPos(3, 1, -12), SelectionGeometry.bounds(BODY, append.appendOrigin(), orientation).min());
  }

  @Test
  void dynamicOnlyDraftUsesManualLocalStartToLocateTheWorldAnchor() {
    assertNull(append(settings(false, BlockPos.ZERO)).appendOrigin());
    assertNull(append(settings(true, new BlockPos(Integer.MIN_VALUE, 0, 0))).appendOrigin());
    var localStart = new BlockPos(-4, 2, 7);
    var append = append(settings(true, localStart));

    assertEquals(new BlockPos(105, 68, -14), append.appendOrigin());
    assertEquals(localStart, SelectionGeometry.bounds(BODY, append.appendOrigin(), append.orientation()).min());
  }

  private static Design append(Settings settings) {
    return Design.defaults().withOptions("", true, "target", false)
        .withSegments(List.of(new Segment(settings, BODY, null, null)), List.of("first"));
  }

  private static Settings settings(boolean customOrigin, BlockPos origin) {
    return new Settings("", 1, 2, Direction.SOUTH, 0, false, BlockPos.ZERO, customOrigin, origin, 1, "", "");
  }
}
