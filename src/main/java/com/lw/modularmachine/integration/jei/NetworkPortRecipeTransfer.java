package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.machine.network.NetworkPortRecipePlan;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.network.NetworkPortPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

record NetworkPortRecipeTransfer(
    RecipeType<MachineRecipeJeiPage> type,
    IRecipeTransferHandlerHelper helper,
    Pending pending) implements IRecipeTransferHandler<NetworkPortMenu, MachineRecipeJeiPage> {
  private static final String PREFIX = "gui.mmcece.network_port.transfer.";
  private static final IRecipeTransferError CONFIGURE = new IRecipeTransferError() {
    @Override
    public Type getType() {
      return Type.COSMETIC;
    }

    @Override
    public int getButtonHighlightColor() {
      return 0;
    }

    @Override
    public void getTooltip(ITooltipBuilder tooltip) {
      tooltip.add(Component.translatable(PREFIX + "configure"));
    }
  };

  @Override
  public Class<NetworkPortMenu> getContainerClass() {
    return NetworkPortMenu.class;
  }

  @Override
  public Optional<MenuType<NetworkPortMenu>> getMenuType() {
    return Optional.of(MachineRegistries.NETWORK_PORT_MENU.get());
  }

  @Override
  public RecipeType<MachineRecipeJeiPage> getRecipeType() {
    return type;
  }

  @Override
  public @Nullable IRecipeTransferError transferRecipe(IRecipeTransferContext<MachineRecipeJeiPage, NetworkPortMenu> context, boolean doTransfer) {
    var menu = context.getContainer();
    var error = transfer(menu, context.getRecipe(), context.getPlayer(), doTransfer, context.getTransferId());
    if (doTransfer) {
      if (error == null) {
        pending.track(menu.status().session(), context.getTransferId(), context::completeRecipeTransfer);
      } else {
        context.completeRecipeTransfer(RecipeTransferResult.REJECTED);
      }
    }
    return error;
  }

  @Override
  @SuppressWarnings("removal")
  public @Nullable IRecipeTransferError transferRecipe(NetworkPortMenu menu, MachineRecipeJeiPage page, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer) {
    return transfer(menu, page, player, doTransfer, 0);
  }

  private @Nullable IRecipeTransferError transfer(NetworkPortMenu menu, MachineRecipeJeiPage page, Player player, boolean doTransfer, int transferId) {
    if (MachineJeiPlugin.runtime == null || player.containerMenu != menu || !menu.port().kind().isInput()) {
      return helper.createInternalError();
    }
    var status = menu.status();
    if (status == null || !status.editable()) {
      return helper.createUserErrorWithTooltip(Component.translatable(PREFIX + "unavailable"));
    }
    var recipe = page.recipes().getFirst();
    var plan = NetworkPortRecipePlan.forRecipe(recipe.value(), status.snapshot().resource());
    var error = plan.error();
    if (error != NetworkPortRecipePlan.Error.NONE) {
      return helper.createUserErrorWithTooltip(Component.translatable(PREFIX + error.name().toLowerCase(Locale.ROOT)));
    }
    if (!doTransfer) {
      return CONFIGURE;
    }
    if (!menu.requestRecipe(recipe.id(), transferId, plan.targets())) {
      return helper.createUserErrorWithTooltip(Component.translatable(PREFIX + "unavailable"));
    }
    return null;
  }

  static final class Pending {
    private record Request(UUID session, int id) {
    }

    private final Map<Request, Consumer<RecipeTransferResult>> callbacks = new HashMap<>();

    void track(UUID session, int id, Consumer<RecipeTransferResult> callback) {
      callbacks.put(new Request(session, id), callback);
    }

    void receive(NetworkPortPayloads.RecipeResult result) {
      var callback = callbacks.remove(new Request(result.session(), result.transferId()));
      if (callback != null) {
        callback.accept(result.applied() ? RecipeTransferResult.SUCCESS : RecipeTransferResult.REJECTED);
      }
    }

    void rejectAll() {
      var pending = List.copyOf(callbacks.values());
      callbacks.clear();
      for (var callback : pending) {
        callback.accept(RecipeTransferResult.REJECTED);
      }
    }
  }
}
