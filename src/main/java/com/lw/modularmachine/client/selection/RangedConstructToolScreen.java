package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.client.preview.MachinePreviewDefinition;
import com.lw.modularmachine.client.preview.StructurePreviewPanel;
import com.lw.modularmachine.client.preview.StructurePreviewScreen;
import com.lw.modularmachine.client.selection.RangedSelectionClient.Target;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Client design draft and selected-world preview using the shared viewer. */
@ApiStatus.Internal
public final class RangedConstructToolScreen extends StructurePreviewScreen {
  private static final GuiTextures.NineSlice BUTTON = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/button.png"),
      0,
      0,
      200,
      20,
      200,
      20,
      2);
  private final ItemStack icon = new ItemStack(MachineRegistries.RANGED_CONSTRUCT_TOOL.get());
  private final RangedSelectionDesignPanel designPanel = new RangedSelectionDesignPanel(this);
  private MachinePreviewDefinition shown;
  private int shownColor = -1;
  private ExportRequest exportRequest;
  private Design exportDesign;
  private Component exportStatus;
  private boolean saving;

  private record ExportRequest(Design design, RangedSelectionCapture capture, String name) {
  }

  public RangedConstructToolScreen() {
    super(Component.translatable("item.mmcece.ranged_construct_tool"));
  }

  void selectionChanged() {
    shown = null;
    shownColor = -1;
    designPanel.selectionChanged();
    setPreview(null, 0xFFFFFF);
    resetPreviewCamera();
  }

  @Override
  protected ItemStack headerIcon() {
    return icon;
  }

  @Override
  protected boolean canEditDynamicLengths() {
    return true;
  }

  @Override
  protected int initialDynamicLength(String key, int minimum, int maximum) {
    int index = RangedSelectionClient.design().keys().indexOf(key);
    return index < 0 ? minimum : RangedSelectionClient.design().segments().get(index).settings().maximum();
  }

  @Override
  protected void dynamicLengthChanged(String key, int acceptedLength) {
    RangedSelectionClient.setLength(key, acceptedLength);
    designPanel.invalidate();
  }

  @Override
  protected Set<String> retainedDynamicKeys() {
    return Set.copyOf(RangedSelectionClient.design().keys());
  }

  @Override
  protected GroupAction dynamicGroupAction(String key) {
    if (!RangedSelectionClient.design().keys().contains(key)) {
      return null;
    }
    return new GroupAction(RangedSelectionClient.text("design_settings"), () -> designPanel.openSegment(key));
  }

  @Override
  protected void rebuildAdditionalWidgets() {
    designPanel.rebuildWidgets(font, field -> addWidget(field));
  }

  void rebuildDesignWidgets() {
    super.init();
  }

  void focusDesignField(EditBox field) {
    setFocused(field);
  }

  void showPartEditor() {
    designPanel.openPart();
  }

  @Override
  protected PreviewArea previewArea(StructurePreviewPanel panel) {
    int left = panel.left() + 18;
    int right = panel.left() + panel.width() - 18;
    if (designPanel.isOpen()) {
      left += designPanel.panelWidth(panel) + 8;
    }
    if (materialListVisible()) {
      right -= materialListWidth() + 8;
    }
    return new PreviewArea(left, panel.top() + 58, Math.max(0, right - left), Math.max(0, panel.height() - 116));
  }

  @Override
  protected int materialPanelWidth(int availableWidth) {
    int maximum = designPanel.isOpen() ? Math.max(120, availableWidth / 3) : availableWidth;
    return super.materialPanelWidth(maximum);
  }

  @Override
  protected int footerHeight() {
    return 24;
  }

  @Override
  protected LayerControls layerControls() {
    var panel = StructurePreviewPanel.forScreen(width, height);
    var pick = button(Target.MAIN);
    int right = panel.left() + panel.width() - 18;
    int available = right - pick.x() - pick.width() - 12;
    int labelWidth = Math.max(40, Math.min(116, available - 108));

    return new LayerControls(right - labelWidth - 108, panel.top() + panel.height() - 28, labelWidth);
  }

  @Override
  protected Component mainGroupTitle() {
    return Target.MAIN.title();
  }

  @Override
  protected Component dynamicGroupTitle(int index) {
    return RangedSelectionClient.segmentTitle(dynamicGroupKey(index));
  }

  @Override
  protected int preparePreview(int budget, long deadline) {
    if (preparingPreview()) {
      return budget;
    }
    int remaining = RangedSelectionClient.advance(budget, deadline);
    var capture = RangedSelectionClient.capture();
    var next = capture == null ? null : capture.definition();
    int nextColor = previewColor();
    if (next != shown || nextColor != shownColor) {
      shown = next;
      shownColor = nextColor;
      setPreview(next, nextColor);
    }

    return remaining;
  }

  private int previewColor() {
    String color = RangedSelectionClient.design().color();
    return color.isEmpty() ? MachineConfig.defaultCasingColor() : Integer.parseInt(color, 16);
  }

  @Override
  public void tick() {
    if (!RangedSelectionClient.valid()) {
      onClose();
      return;
    }
    advanceExport();
    super.tick();
    if (!saving && exportRequest == null && exportDesign != RangedSelectionClient.design()) {
      exportStatus = null;
    }
    advanceExport();
  }

  private void requestExport() {
    if (saving || exportRequest != null) {
      return;
    }
    exportStatus = null;
    if (!designPanel.commitPending()) {
      return;
    }
    RangedSelectionClient.clearInteractionError();
    exportDesign = RangedSelectionClient.design();
    try {
      String name = RangedSelectionExportFile.fileName(exportDesign.fileName());
      exportRequest = new ExportRequest(exportDesign, RangedSelectionClient.capture(), name);
      exportStatus = RangedSelectionClient.text("export_waiting");
      advanceExport();
    } catch (IllegalArgumentException invalid) {
      exportStatus = RangedSelectionClient.text("export_invalid_name");
    }
  }

  private void advanceExport() {
    var request = exportRequest;
    if (request == null) {
      return;
    }
    var capture = RangedSelectionClient.capture();
    if (capture != request.capture() || request.design() != RangedSelectionClient.design()) {
      cancelExport("export_changed");
      return;
    }
    var error = capture.error();
    if (!error.getString().isEmpty()) {
      exportRequest = null;
      exportStatus = error;
      return;
    }
    if (!capture.currentDefinition() || preparingPreview() || shownColor != previewColor()) {
      return;
    }
    if (previewFailed()) {
      cancelExport("export_invalid");
      return;
    }
    var lengths = completedPreviewLengths(capture.definition());
    var snapshot = capture.exportSnapshot();
    if (lengths == null || snapshot == null) {
      cancelExport("export_empty");
      return;
    }
    for (var entry : lengths.entrySet()) {
      int index = request.design().keys().indexOf(entry.getKey());
      if (index < 0 || request.design().segments().get(index).settings().maximum() != entry.getValue()) {
        cancelExport("export_invalid");
        return;
      }
    }
    exportRequest = null;
    saving = true;
    exportStatus = RangedSelectionClient.text("export_saving");
    var client = minecraft;
    var directory = MachineDefinitionConfig.directory().resolve("mmcece");
    CompletableFuture.supplyAsync(() -> {
      try {
        return RangedSelectionExportFile
            .save(directory, request.name(), snapshot.root(request.design(), request.name()));
      } catch (IOException failure) {
        throw new CompletionException(failure);
      }
    }, Util.ioPool()).whenComplete((saved, failure) -> client.execute(() -> {
      saving = false;
      if (failure == null) {
        exportStatus = RangedSelectionClient.text("export_saved", saved.file().toString());
      } else {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure
            .getCause() : failure;
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        exportStatus = RangedSelectionClient.text("export_failed", detail.substring(0, Math.min(512, detail.length())));
      }
      if (RangedSelectionClient.capture() == request.capture() && client.player != null) {
        client.player.displayClientMessage(exportStatus, false);
      }
    }));
  }

  private void cancelExport(String reason) {
    exportRequest = null;
    if (RangedSelectionClient.valid()) {
      exportDesign = RangedSelectionClient.design();
    }
    exportStatus = RangedSelectionClient.text(reason);
  }

  @Override
  public void removed() {
    if (exportRequest != null) {
      cancelExport("export_changed");
    }
    super.removed();
  }

  private record PickButton(int x, int y, int width) {
    boolean contains(double mouseX, double mouseY) {
      return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 20;
    }
  }

  private PickButton button(Target target) {
    var panel = StructurePreviewPanel.forScreen(width, height);
    int buttonWidth = Math.min(96, Math.max(40, panel.width() - 36));
    int y = panel.top() + panel.height() - (target == Target.MAIN ? 52 : 28);

    return new PickButton(panel.left() + 18, y, buttonWidth);
  }

  private PickButton designButton() {
    var panel = StructurePreviewPanel.forScreen(width, height);
    return new PickButton(panel.left() + panel.width() - 116, panel.top() + 10, 80);
  }

  private PickButton exportButton() {
    var panel = StructurePreviewPanel.forScreen(width, height);
    return new PickButton(panel.left() + panel.width() - 114, panel.top() + panel.height() - 52, 96);
  }

  private void drawButton(GuiGraphics graphics, PickButton button, Component title, int mouseX, int mouseY) {
    GuiTextures.blitNineSlice(graphics, BUTTON, button.x(), button.y(), button.width(), 20);
    String label = font.plainSubstrByWidth(title.getString(), button.width() - 8);
    graphics.drawString(
        font,
        label,
        button.x() + (button.width() - font.width(label)) / 2,
        button.y() + 6,
        0xFF202020,
        false);
    if (button.contains(mouseX, mouseY)) {
      graphics.renderOutline(button.x(), button.y(), button.width(), 20, 0xFF205A70);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    designPanel.render(graphics, mouseX, mouseY, partialTick);
    drawButton(graphics, designButton(), RangedSelectionClient.text("design_settings"), mouseX, mouseY);
    for (var target : new Target[]{Target.MAIN, Target.DYNAMIC}) {
      var button = button(target);
      drawButton(graphics, button, target.title(), mouseX, mouseY);
      if (button.contains(mouseX, mouseY)) {
        graphics.renderTooltip(font, RangedSelectionClient.text("pick_help", target.title()), mouseX, mouseY);
      }
    }
    var export = exportButton();
    String exportLabel = saving ? "export_saving" : exportRequest != null ? "export_waiting" : "export";
    drawButton(graphics, export, RangedSelectionClient.text(exportLabel), mouseX, mouseY);
    if (export.contains(mouseX, mouseY)) {
      graphics.renderTooltip(font, font.split(RangedSelectionClient.text("export_help"), 260), mouseX, mouseY);
    }
    var first = button(Target.MAIN);
    int statusX = first.x() + first.width() + 12;
    int available = export.x() - 12 - statusX;
    var error = RangedSelectionClient.error();
    var status = exportStatus == null ? RangedSelectionClient.status() : exportStatus;
    if (!error.getString().isEmpty()) {
      status = error;
    }
    if (available > 0) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(status.getString(), available),
          statusX,
          first.y() + 6,
          0xFF606060,
          false);
      if (mouseX >= statusX && mouseX < statusX + available && mouseY >= first.y() && mouseY < first.y() + 20) {
        graphics.renderTooltip(font, font.split(status, 240), mouseX, mouseY);
      }
    }
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0) {
      if (exportButton().contains(mouseX, mouseY)) {
        requestExport();
        return true;
      }
      if (designButton().contains(mouseX, mouseY)) {
        designPanel.toggle();
        return true;
      }
      for (var target : new Target[]{Target.MAIN, Target.DYNAMIC}) {
        if (button(target).contains(mouseX, mouseY)) {
          if (designPanel.commitPending()) {
            RangedSelectionClient.beginPick(target);
          }
          return true;
        }
      }
    }
    if (designPanel.mouseClicked(mouseX, mouseY, button)) {
      return true;
    }

    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    return designPanel.mouseScrolled(x, y, scrollX, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
