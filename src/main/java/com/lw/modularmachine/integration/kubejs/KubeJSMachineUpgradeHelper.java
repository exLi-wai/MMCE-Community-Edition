package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.upgrade.UpgradeType;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.machine.upgrade.UpgradeEntryResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Detached item helpers. These methods never replace a live slot or bypass
 * inventory transactions.
 */
public final class KubeJSMachineUpgradeHelper {
  private KubeJSMachineUpgradeHelper() {
  }

  public static void registerSupportedItem(ItemStack stack) {
    KubeJSUpgradeDeclarations.active().support(stack);
  }

  public static void addFixedUpgrade(ItemStack stack, String name) {
    KubeJSUpgradeDeclarations.active().bind(stack, name);
  }

  public static UpgradeType getUpgrade(String name) {
    if (name == null) {
      return null;
    }
    var id = ResourceLocation.tryParse(name.contains(":") ? name : "kubejs:" + name);
    var registration = KubeJSUpgradeDeclarations.activeOrNull();
    if (registration != null) {
      return id == null ? null : registration.registeredType(id);
    }
    var type = id == null ? null : UpgradeCatalogService.snapshot().types().get(id);
    return type == null ? null : type.type();
  }

  public static UpgradeType castToSimpleDynamicMachineUpgrade(UpgradeType type) {
    return type != null && type.dynamic() ? type : null;
  }

  public static UpgradeType castToSimpleMachineUpgrade(UpgradeType type) {
    return type != null && !type.dynamic() ? type : null;
  }

  public static ItemStack addUpgradeToIItemStack(ItemStack source, String name) {
    var type = getUpgrade(name);
    var registration = KubeJSUpgradeDeclarations.activeOrNull();
    if (source == null
        || source.isEmpty()
        || type == null
        || !(registration == null ? UpgradeCatalogService.snapshot().supportedItems()
            .contains(source.getItem().builtInRegistryHolder().key().location()) : registration
                .supports(source.getItem().builtInRegistryHolder().key().location()))) {
      throw new IllegalArgumentException("Unknown upgrade or unsupported upgrade item");
    }
    var stack = source.copy();
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    data.putString(UpgradeEntryResolver.TYPE_MARKER, type.id().toString());
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    return stack;
  }
}
