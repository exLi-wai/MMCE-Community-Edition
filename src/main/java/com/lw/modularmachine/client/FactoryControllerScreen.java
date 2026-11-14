package com.lw.modularmachine.client;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import com.lw.modularmachine.util.client.search.NameSearch;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;
import com.lw.modularmachine.client.controller.ControllerClientPublisher;

public final class FactoryControllerScreen extends AbstractContainerScreen<FactoryControllerMenu> {
  private static final ResourceLocation FACTORY_TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guifactory.png");
  private static final ResourceLocation ELEMENT_TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guifactoryelements.png");
  private static final int MAX_VISIBLE_THREADS = 6;
  private static final int THREAD_X = 8;
  private static final int THREAD_Y = 8;
  private static final int THREAD_WIDTH = 86;
  private static final int THREAD_HEIGHT = 32;
  private static final int ELEMENT_TEXTURE_SIZE = 256;
  private static final int THREAD_STEP = 33;
  private static final int FONT_COLOR = 0x222222;
  private static final int INFO_COLOR = 0xFFFFFF;
  private static final int SCROLLBAR_X = 94;
  private static final int SCROLLBAR_Y = 8;
  private static final int SCROLLBAR_HEIGHT = 197;
  private static final int EDITOR_WIDTH = 268, EDITOR_HEIGHT = 201, RECIPES_PER_PAGE = 3;
  private static final GuiTextures.NineSlice EDITOR_BACKGROUND = new GuiTextures.NineSlice(
      ELEMENT_TEXTURE,
      0,
      0,
      86,
      32,
      256,
      256,
      1);
  private static final GuiTextures.NineSlice EDITOR_BUTTON = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/button.png"),
      0,
      0,
      200,
      20,
      200,
      20,
      2);
  private final LegacyGuiScrollbar scrollbar = new LegacyGuiScrollbar();
  private final NameSearch nameSearch = new NameSearch();
  private String editingThread, query = "", parallelText = "";
  private ResourceLocation editingMachine;
  private EditBox search, parallel;
  private List<RecipeHolder<MachineRecipe>> recipes = List.of(), filteredRecipes = List.of();
  private Map<ResourceLocation, Component> recipeLabels = Map.of();
  private Map<ResourceLocation, List<String>> recipeSearchNames = Map.of();
  private int recipePage;
  private PendingAction pending;
  private Component editorMessage = Component.empty();

  private record PendingAction(
      MachinePayloads.FactoryAction action,
      long revision,
      ResourceLocation recipe,
      boolean enabled,
      int parallelLimit,
      long sentAt) {
    boolean applied(MachinePayloads.FactoryStatusThread thread) {
      return thread.revision() > revision && switch (action) {
        case SET_LOCKED_RECIPE -> Objects.equals(thread.lockedRecipe(), recipe);
        case SET_ENABLED -> thread.enabled() == enabled;
        case SET_PARALLEL_LIMIT -> thread.parallelLimit() == parallelLimit;
      };
    }
  }

  public FactoryControllerScreen(FactoryControllerMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 280;
    imageHeight = 213;
  }

  @Override
  protected void init() {
    rememberFields();
    super.init();
    MachineControllerStatusClient.attach(this);
    if (editingThread != null) {
      if (editedThread() == null) {
        closeEditor();
      } else {
        installFields();
      }
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    if (editingThread != null) {
      renderEditor(graphics, mouseX, mouseY, partialTick);
    } else {
      renderTooltip(graphics, mouseX, mouseY);
      if (threadAt(mouseX, mouseY) != null && menu.getCarried().isEmpty()) {
        graphics.renderTooltip(font, tr("open"), mouseX, mouseY);
      }
    }
  }

  private static Component tr(String key, Object... args) {
    return Component.translatable("gui.mmcece.factory_thread." + key, args);
  }

  private int editorLeft() {
    return leftPos + 6;
  }

  private int editorTop() {
    return topPos + 6;
  }

  private MachinePayloads.FactoryStatusThread editedThread() {
    return editingThread == null ? null : threads().stream().filter(thread -> thread.threadId().equals(editingThread))
        .findFirst().orElse(null);
  }

  private MachinePayloads.FactoryStatusThread threadAt(double x, double y) {
    double localX = x - leftPos - THREAD_X, localY = y - topPos - THREAD_Y;
    if (localX < 0 || localX >= THREAD_WIDTH || localY < 0 || localY >= MAX_VISIBLE_THREADS * THREAD_STEP) {
      return null;
    }
    int row = (int) localY / THREAD_STEP;
    if (localY - row * THREAD_STEP >= THREAD_HEIGHT) {
      return null;
    }
    var threads = threads();
    int index = Math.min(scrollbar.current(), Math.max(0, threads.size() - MAX_VISIBLE_THREADS)) + row;
    return index < threads.size() ? threads.get(index) : null;
  }

  private void openEditor(MachinePayloads.FactoryStatusThread thread) {
    editingThread = thread.threadId();
    editingMachine = thread.machineId();
    query = "";
    parallelText = Integer.toString(thread.parallelLimit());
    recipePage = 0;
    pending = null;
    editorMessage = Component.empty();
    installFields();
    refreshRecipes();
  }

  private void rememberFields() {
    if (search != null) {
      query = search.getValue();
    }
    if (parallel != null) {
      parallelText = parallel.getValue();
    }
  }

  private void installFields() {
    if (search != null) {
      removeWidget(search);
    }
    if (parallel != null) {
      removeWidget(parallel);
    }
    search = new EditBox(font, editorLeft() + 8, editorTop() + 70, 186, 18, tr("search"));
    search.setMaxLength(256);
    search.setValue(query);
    search.setResponder(value -> {
      query = value;
      recipePage = 0;
      filterRecipes();
    });
    addWidget(search);
    parallel = new EditBox(font, editorLeft() + 152, editorTop() + 42, 58, 18, tr("parallel"));
    parallel.setMaxLength(10);
    parallel.setFilter(value -> value.matches("[0-9]*"));
    parallel.setValue(parallelText);
    parallel.setResponder(value -> {
      parallelText = value;
      parallel.setTextColor(parallelValue() > 0 ? 0xFFE0E0E0 : 0xFFFF7777);
    });
    addWidget(parallel);
    setFocused(null);
  }

  private void closeEditor() {
    if (search != null) {
      removeWidget(search);
    }
    if (parallel != null) {
      removeWidget(parallel);
    }
    setFocused(null);
    setDragging(false);
    search = null;
    parallel = null;
    editingThread = null;
    editingMachine = null;
    recipes = List.of();
    filteredRecipes = List.of();
    recipeLabels = Map.of();
    recipeSearchNames = Map.of();
    pending = null;
  }

  private void refreshRecipes() {
    var connection = minecraft.getConnection();
    recipes = connection == null ? List.of() : connection.getRecipeManager()
        .getAllRecipesFor(MachineRecipeRegistries.ITEM_TYPE.get()).stream()
        .filter(holder -> holder.value().machineId().equals(editingMachine))
        .sorted(Comparator.comparing(holder -> holder.id().toString())).toList();
    recipeLabels = recipes.stream()
        .collect(Collectors.toUnmodifiableMap(RecipeHolder::id, holder -> recipeLabel(holder.value())));
    recipeSearchNames = recipes.stream()
        .collect(Collectors.toUnmodifiableMap(RecipeHolder::id, holder -> recipeNames(holder.value())));
    filterRecipes();
  }

  private static Component recipeLabel(MachineRecipe recipe) {
    var chemicalInputs = Stream.concat(recipe.chemicals().inputs().stream(), recipe.chemicals().perTick().stream())
        .toList();

    return tr(
        "recipe_items",
        resourceNames(recipe.inputs(), chemicalInputs),
        resourceNames(recipe.outputs(), recipe.chemicals().outputs()));
  }

  private static Component resourceNames(List<MachineRecipe.ItemAmount> items, List<ChemicalRequirement> chemicals) {
    var names = Stream.concat(
        items.stream().map(item -> item.displayStack().getHoverName()),
        chemicals.stream().map(FactoryControllerScreen::chemicalName)).toList();

    return names.isEmpty() ? tr("no_items") : ComponentUtils.formatList(names, name -> name);
  }

  private static Component chemicalName(ChemicalRequirement chemical) {
    return ChemicalDisplay.describe(new ChemicalStackData(chemical.chemical(), chemical.amount())).name();
  }

  private static List<String> recipeNames(MachineRecipe recipe) {
    var items = Stream.concat(recipe.inputs().stream(), recipe.outputs().stream())
        .map(item -> item.displayStack().getHoverName().getString());
    var fluids = Stream.of(recipe.fluidInputs(), recipe.fluidOutputs(), List.of(recipe.fluidPerTick()))
        .flatMap(List::stream).filter(fluid -> !fluid.isEmpty()).map(fluid -> fluid.getHoverName().getString());
    var chemicals = Stream.of(recipe.chemicals().inputs(), recipe.chemicals().outputs(), recipe.chemicals().perTick())
        .flatMap(List::stream).map(value -> chemicalName(value).getString());

    return Stream.of(items, fluids, chemicals).flatMap(stream -> stream).map(name -> name.toLowerCase(Locale.ROOT))
        .distinct().toList();
  }

  private void filterRecipes() {
    if (query.isBlank()) {
      filteredRecipes = recipes;
    } else {
      var filter = nameSearch.query(query);
      filteredRecipes = recipes.stream().filter(holder -> recipeSearchNames.get(holder.id()).stream().anyMatch(filter))
          .toList();
    }
    recipePage = Math.clamp(recipePage, 0, recipePages() - 1);
  }

  private int recipePages() {
    return Math.max(1, (filteredRecipes.size() + RECIPES_PER_PAGE - 1) / RECIPES_PER_PAGE);
  }

  private int parallelValue() {
    try {
      int value = Integer.parseInt(parallelText);
      return value > 0 ? value : -1;
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private static boolean busy(MachinePayloads.FactoryStatusThread thread) {
    return thread.duration() > 0
        || thread.runId().getMostSignificantBits() != 0
        || thread.runId().getLeastSignificantBits() != 0
        || "RUNNING".equals(thread.state())
        || "OUTPUT_PLANNING".equals(thread.reason())
        || "UPGRADE_DATA_PENDING".equals(thread.reason());
  }

  private boolean canEdit(MachinePayloads.FactoryStatusThread thread) {
    return thread != null && menu.status() != null && menu.status().persistentStateValid() && pending == null;
  }

  private void sendAction(MachinePayloads.FactoryAction action, ResourceLocation recipe) {
    var thread = editedThread();
    if (!canEdit(thread) || action != MachinePayloads.FactoryAction.SET_ENABLED && busy(thread)) {
      return;
    }
    int requestedParallel = parallelValue();
    if (action == MachinePayloads.FactoryAction.SET_PARALLEL_LIMIT
        && (requestedParallel < 1 || requestedParallel == thread.parallelLimit())) {
      return;
    }
    if (action == MachinePayloads.FactoryAction.SET_LOCKED_RECIPE && Objects.equals(recipe, thread.lockedRecipe())) {
      return;
    }
    pending = new PendingAction(
        action,
        thread.revision(),
        recipe,
        !thread.enabled(),
        requestedParallel,
        Util.getMillis());
    parallel.active = false;
    editorMessage = tr("waiting");
    switch (action) {
      case SET_LOCKED_RECIPE -> menu.sendLockAction(thread.threadId(), recipe);
      case SET_ENABLED -> menu.sendEnabledAction(thread.threadId(), !thread.enabled());
      case SET_PARALLEL_LIMIT ->
        menu.sendParallelLimitAction(thread.threadId(), requestedParallel, thread.upgradeRevision());
    }
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (editingThread == null) {
      return;
    }
    var thread = editedThread();
    if (thread == null || !thread.machineId().equals(editingMachine)) {
      closeEditor();
      return;
    }
    if (pending != null) {
      if (pending.applied(thread)) {
        pending = null;
        editorMessage = tr("applied");
      } else if (Util.getMillis() - pending.sentAt() > 4000) {
        pending = null;
        editorMessage = tr("not_applied");
      }
    }
    parallel.active = canEdit(thread) && !busy(thread);
  }

  private boolean editorHit(double mouseX, double mouseY, int x, int y, int width, int height) {
    return mouseX >= editorLeft() + x
        && mouseX < editorLeft() + x + width
        && mouseY >= editorTop() + y
        && mouseY < editorTop() + y + height;
  }

  private void drawEditorButton(GuiGraphics graphics, int x, int y, int width, int height, Component label, boolean active, int mouseX, int mouseY) {
    GuiTextures.blitNineSlice(graphics, EDITOR_BUTTON, editorLeft() + x, editorTop() + y, width, height);
    if (active && editorHit(mouseX, mouseY, x, y, width, height)) {
      graphics.renderOutline(editorLeft() + x, editorTop() + y, width, height, 0xFF205A70);
    }
    String text = font.plainSubstrByWidth(label.getString(), Math.max(0, width - 4));
    graphics.drawString(
        font,
        text,
        editorLeft() + x + (width - font.width(text)) / 2,
        editorTop() + y + (height - font.lineHeight) / 2,
        active ? 0xFF202020 : 0xFF888888,
        false);
  }

  private void renderEditor(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    var thread = editedThread();
    if (thread == null) {
      return;
    }
    boolean editable = canEdit(thread);
    boolean idle = editable && !busy(thread);
    parallel.active = idle;
    List<Component> tooltip = List.of();
    graphics.pose().pushPose();
    graphics.pose().translate(0, 0, 400);
    try {
      GuiTextures.blitNineSlice(graphics, EDITOR_BACKGROUND, editorLeft(), editorTop(), EDITOR_WIDTH, EDITOR_HEIGHT);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(tr("title", thread.threadId()).getString(), 222),
          editorLeft() + 8,
          editorTop() + 7,
          FONT_COLOR,
          false);
      drawEditorButton(graphics, 240, 4, 20, 16, Component.literal("X"), true, mouseX, mouseY);
      var lockedName = tr("unlocked");
      if (thread.lockedRecipe() != null) {
        lockedName = recipeLabels.getOrDefault(
            thread.lockedRecipe(),
            Component.translatable("menu.mmcece.factory.reason.locked_recipe_missing"));
      }
      var locked = tr("locked", lockedName);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(locked.getString(), 174),
          editorLeft() + 8,
          editorTop() + 24,
          FONT_COLOR,
          false);
      drawEditorButton(graphics, 190, 20, 70, 16, tr("unlock"), idle && thread.lockedRecipe() != null, mouseX, mouseY);
      drawEditorButton(graphics, 8, 42, 80, 18, tr(thread.enabled() ? "disable" : "enable"), editable, mouseX, mouseY);
      graphics.drawString(font, tr("parallel"), editorLeft() + 94, editorTop() + 47, FONT_COLOR, false);
      parallel.render(graphics, mouseX, mouseY, partialTick);
      drawEditorButton(
          graphics,
          218,
          42,
          42,
          18,
          tr("apply"),
          idle && parallelValue() > 0 && parallelValue() != thread.parallelLimit(),
          mouseX,
          mouseY);
      search.render(graphics, mouseX, mouseY, partialTick);
      drawEditorButton(graphics, 200, 70, 60, 18, tr("search"), true, mouseX, mouseY);
      int first = recipePage * RECIPES_PER_PAGE;
      for (int row = 0; row < Math.min(RECIPES_PER_PAGE, filteredRecipes.size() - first); row++) {
        var recipe = filteredRecipes.get(first + row);
        var label = recipeLabels.get(recipe.id());
        int y = 94 + row * 25;
        boolean selected = recipe.id().equals(thread.lockedRecipe());
        graphics.fill(
            editorLeft() + 8,
            editorTop() + y,
            editorLeft() + 260,
            editorTop() + y + 23,
            selected ? 0xFFD4E8D0 : 0xFFD5D5D5);
        var icon = recipe.value().outputs().isEmpty() ? ItemStack.EMPTY : recipe.value()
            .getResultItem(minecraft.level.registryAccess());
        if (!icon.isEmpty()) {
          graphics.renderItem(icon, editorLeft() + 11, editorTop() + y + 3);
        }
        graphics.drawString(
            font,
            font.plainSubstrByWidth(label.getString(), 169),
            editorLeft() + 32,
            editorTop() + y + 7,
            FONT_COLOR,
            false);
        drawEditorButton(
            graphics,
            210,
            y + 3,
            46,
            18,
            tr(selected ? "selected" : "lock"),
            idle && !selected,
            mouseX,
            mouseY);
        if (editorHit(mouseX, mouseY, 8, y, 252, 23)) {
          tooltip = List.of(label);
        }
      }
      if (filteredRecipes.isEmpty()) {
        graphics.drawString(font, tr("no_recipes"), editorLeft() + 12, editorTop() + 106, FONT_COLOR, false);
      }
      drawEditorButton(graphics, 8, 173, 24, 16, Component.literal("<"), recipePage > 0, mouseX, mouseY);
      var page = Component.literal((recipePage + 1) + " / " + recipePages());
      graphics.drawString(font, page, editorLeft() + 36, editorTop() + 177, FONT_COLOR, false);
      drawEditorButton(
          graphics,
          94,
          173,
          24,
          16,
          Component.literal(">"),
          recipePage + 1 < recipePages(),
          mouseX,
          mouseY);
      Component message = busy(thread) ? tr("busy") : pending != null ? tr("waiting") : editorMessage;
      if (message.getString().isEmpty()) {
        message = tr("hint");
      }
      graphics.drawString(
          font,
          font.plainSubstrByWidth(message.getString(), 250),
          editorLeft() + 8,
          editorTop() + 191,
          busy(thread) ? 0xFF8A2B20 : FONT_COLOR,
          false);
      if (editorHit(mouseX, mouseY, 8, 20, 174, 17)) {
        tooltip = List.of(locked);
      } else if (editorHit(mouseX, mouseY, 94, 42, 166, 18)) {
        tooltip = List.of(tr("parallel_current", thread.parallelLimit()), tr("parallel_help"));
      } else if (editorHit(mouseX, mouseY, 8, 190, 250, 11)) {
        tooltip = List.of(message);
      }
    } finally {
      graphics.pose().popPose();
    }
    if (!tooltip.isEmpty()) {
      graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(FACTORY_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    List<MachinePayloads.FactoryStatusThread> threads = threads();
    int maxScroll = Math.max(0, threads.size() - MAX_VISIBLE_THREADS);
    scrollbar.position(leftPos + SCROLLBAR_X, topPos + SCROLLBAR_Y).height(SCROLLBAR_HEIGHT).range(0, maxScroll, 1)
        .draw(graphics);
    int threadScroll = Math.min(scrollbar.current(), maxScroll);
    for (int row = 0; row < Math.min(MAX_VISIBLE_THREADS, threads.size() - threadScroll); row++) {
      MachinePayloads.FactoryStatusThread thread = threads.get(row + threadScroll);
      int x = leftPos + THREAD_X;
      int y = topPos + THREAD_Y + row * THREAD_STEP;
      graphics
          .blit(ELEMENT_TEXTURE, x, y, 0, 0, THREAD_WIDTH, THREAD_HEIGHT, ELEMENT_TEXTURE_SIZE, ELEMENT_TEXTURE_SIZE);
      int progressWidth = progressWidth(thread);
      if (progressWidth > 0) {
        if ("RUNNING".equals(thread.state()) || "OUTPUT_PLANNING".equals(thread.reason())) {
          graphics.setColor(.6F, 1F, .75F, 1F);
        } else {
          graphics.setColor(1F, .6F, .6F, 1F);
        }
        graphics.blit(
            ELEMENT_TEXTURE,
            x,
            y,
            0,
            0,
            progressWidth,
            THREAD_HEIGHT,
            ELEMENT_TEXTURE_SIZE,
            ELEMENT_TEXTURE_SIZE);
        graphics.setColor(1F, 1F, 1F, 1F);
      }
    }
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    List<MachinePayloads.FactoryStatusThread> threads = threads();
    int threadScroll = Math.min(scrollbar.current(), Math.max(0, threads.size() - MAX_VISIBLE_THREADS));
    for (int row = 0; row < Math.min(MAX_VISIBLE_THREADS, threads.size() - threadScroll); row++) {
      drawThreadStatus(graphics, threads.get(row + threadScroll), row);
    }
    graphics.pose().pushPose();
    graphics.pose().scale(.72F, .72F, 1F);
    int infoX = (int) (113 / .72F);
    int infoY = 12;
    var status = menu.status();
    boolean formed = status != null && "CONFIRMED".equals(status.structureState());
    Component blueprint = ControllerStatusLabels.blueprint(menu);
    if (blueprint != null || !formed) {
      infoY = drawWrapped(
          graphics,
          Component.translatable(
              "menu.mmcece.controller.blueprint",
              blueprint == null ? ControllerStatusLabels.none() : blueprint),
          infoX,
          infoY) + 5;
    }
    if (status == null) {
      drawWrapped(graphics, Component.translatable("menu.mmcece.controller.status_waiting"), infoX, infoY);
      graphics.pose().popPose();
      return;
    }
    Component structure = ControllerStatusLabels.structure(status.structureState(), status.machineId());
    infoY = drawWrapped(
        graphics,
        Component.translatable(
            "menu.mmcece.controller.structure",
            structure == null ? ControllerStatusLabels.none() : structure),
        infoX,
        infoY) + 5;
    infoY = drawWrapped(
        graphics,
        Component.translatable("menu.mmcece.controller.status", ControllerStatusLabels.factoryState(status)),
        infoX,
        infoY) + 5;
    infoY = drawWrapped(
        graphics,
        Component.translatable("menu.mmcece.factory.threads", threads.size()),
        infoX,
        infoY) + 5;
    if ("INVALID".equals(status.structureState())) {
      infoY = drawWrapped(
          graphics,
          Component.translatable(
              "menu.mmcece.factory.structure_failure",
              Component.translatable("jade.mmcece.failure." + status.structureFailure().toLowerCase(Locale.ROOT))),
          infoX,
          infoY) + 5;
    }
    ControllerStatusLabels
        .drawPerformance(graphics, font, infoX, infoY, status.averageTickMicros(), status.averageSearchMicros());
    ControllerClientPublisher
        .drawGui(menu.factory(), menu.guiCustomData(), graphics, font, infoX, infoY + 25, 187, (int) (247 / .72F));
    graphics.pose().popPose();
  }

  private int drawWrapped(GuiGraphics graphics, Component text, int x, int y) {
    for (var line : font.split(text, 187)) {
      graphics.drawString(font, line, x, y, INFO_COLOR, true);
      y += 10;
    }

    return y;
  }

  private void drawThreadStatus(GuiGraphics graphics, MachinePayloads.FactoryStatusThread thread, int row) {
    graphics.pose().pushPose();
    graphics.pose().scale(.72F, .72F, 1F);
    int x = (int) ((THREAD_X + 2) / .72F);
    int y = (int) ((THREAD_Y + row * THREAD_STEP + 2) / .72F);
    graphics.drawString(font, Component.literal(thread.threadId()), x, y, FONT_COLOR, false);
    Component threadState = Component
        .translatable("menu.mmcece.factory.thread_state." + thread.state().toLowerCase(Locale.ROOT));
    if (thread.duration() > 0 && "WAITING".equals(thread.state()) && !"NONE".equals(thread.reason())) {
      threadState = Component.translatable("menu.mmcece.factory.reason." + thread.reason().toLowerCase(Locale.ROOT));
    }
    graphics.drawString(font, threadState, x, y + 12, FONT_COLOR, false);
    if (thread.duration() == 0 && thread.parallelLimit() > 1) {
      graphics.drawString(font, Component.literal("x" + thread.parallelLimit()), x, y + 22, FONT_COLOR, false);
    }
    if (thread.duration() > 0) {
      graphics.drawString(
          font,
          Component.translatable(
              "menu.mmcece.controller.progress",
              ControllerStatusLabels.percent(thread.progress(), thread.duration()) + "%"),
          x,
          y + 22,
          FONT_COLOR,
          false);
    } else if (!"NONE".equals(thread.reason())) {
      graphics.drawString(
          font,
          Component.translatable("menu.mmcece.factory.reason." + thread.reason().toLowerCase(Locale.ROOT)),
          x,
          y + 22,
          FONT_COLOR,
          false);
    }
    graphics.pose().popPose();
  }

  private static int progressWidth(MachinePayloads.FactoryStatusThread thread) {
    if (thread.duration() <= 0 || thread.progress() <= 0) {
      return 0;
    }

    return (int) Math.min(THREAD_WIDTH, (long) THREAD_WIDTH * thread.progress() / thread.duration());
  }

  private List<MachinePayloads.FactoryStatusThread> threads() {
    var status = menu.status();
    return status == null ? List.of() : status.threads();
  }

  public FactoryControllerMenu getMenu() {
    return menu;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (editingThread != null) {
      if (editorHit(mouseX, mouseY, 8, 94, 252, 73) && scrollY != 0) {
        recipePage = Math.clamp(recipePage + (scrollY > 0 ? -1 : 1), 0, recipePages() - 1);
      }
      return true;
    }
    int maxScroll = Math.max(0, threads().size() - MAX_VISIBLE_THREADS);
    if (maxScroll > 0 && scrollY != 0) {
      scrollbar.wheel(scrollY > 0 ? 1 : -1);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (editingThread != null) {
      if (!editorHit(mouseX, mouseY, 0, 0, EDITOR_WIDTH, EDITOR_HEIGHT)) {
        if (button == 0) {
          closeEditor();
        }
        return true;
      }
      if (button != 0) {
        return true;
      }
      if (editorHit(mouseX, mouseY, 240, 4, 20, 16)) {
        closeEditor();
      } else if (editorHit(mouseX, mouseY, 8, 70, 186, 18)) {
        search.mouseClicked(mouseX, mouseY, button);
        setFocused(search);
        setDragging(true);
      } else if (parallel.active && editorHit(mouseX, mouseY, 152, 42, 58, 18)) {
        parallel.mouseClicked(mouseX, mouseY, button);
        setFocused(parallel);
        setDragging(true);
      } else {
        setFocused(null);
        if (editorHit(mouseX, mouseY, 190, 20, 70, 16)) {
          sendAction(MachinePayloads.FactoryAction.SET_LOCKED_RECIPE, null);
        } else if (editorHit(mouseX, mouseY, 8, 42, 80, 18)) {
          sendAction(MachinePayloads.FactoryAction.SET_ENABLED, null);
        } else if (editorHit(mouseX, mouseY, 218, 42, 42, 18)) {
          sendAction(MachinePayloads.FactoryAction.SET_PARALLEL_LIMIT, null);
        } else if (editorHit(mouseX, mouseY, 200, 70, 60, 18)) {
          recipePage = 0;
          refreshRecipes();
        } else if (editorHit(mouseX, mouseY, 8, 173, 24, 16)) {
          recipePage = Math.max(0, recipePage - 1);
        } else if (editorHit(mouseX, mouseY, 94, 173, 24, 16)) {
          recipePage = Math.min(recipePages() - 1, recipePage + 1);
        } else if (editorHit(mouseX, mouseY, 8, 94, 252, 73)) {
          int localY = (int) (mouseY - editorTop() - 94);
          int row = localY / 25;
          int index = recipePage * RECIPES_PER_PAGE + row;
          if (localY % 25 < 23 && row < RECIPES_PER_PAGE && index < filteredRecipes.size()) {
            sendAction(MachinePayloads.FactoryAction.SET_LOCKED_RECIPE, filteredRecipes.get(index).id());
          }
        }
      }
      return true;
    }
    var thread = threadAt(mouseX, mouseY);
    if (button == 0 && thread != null && menu.getCarried().isEmpty()) {
      openEditor(thread);
      return true;
    }
    scrollbar.click(mouseX, mouseY);
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    if (editingThread != null) {
      if (getFocused() instanceof EditBox edit) {
        edit.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
      return true;
    }
    if (button == 0) {
      scrollbar.click(mouseX, mouseY);
    }

    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (editingThread != null) {
      setDragging(false);
      if (getFocused() instanceof EditBox edit) {
        edit.mouseReleased(mouseX, mouseY, button);
      }
      return true;
    }
    return super.mouseReleased(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (editingThread == null) {
      return super.keyPressed(keyCode, scanCode, modifiers);
    }
    if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
      closeEditor();
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_TAB) {
      setFocused(getFocused() == search && parallel.active ? parallel : search);
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      if (getFocused() == parallel) {
        sendAction(MachinePayloads.FactoryAction.SET_PARALLEL_LIMIT, null);
      } else {
        recipePage = 0;
        refreshRecipes();
      }
      return true;
    }
    if (getFocused() instanceof EditBox edit) {
      edit.keyPressed(keyCode, scanCode, modifiers);
    }
    // Do not forward inventory/hotbar shortcuts through the modal editor.
    return true;
  }

  @Override
  public boolean charTyped(char codePoint, int modifiers) {
    if (editingThread != null) {
      return getFocused() instanceof EditBox edit && edit.charTyped(codePoint, modifiers);
    }
    return super.charTyped(codePoint, modifiers);
  }

  @Override
  public void removed() {
    closeEditor();
    super.removed();
  }
}
