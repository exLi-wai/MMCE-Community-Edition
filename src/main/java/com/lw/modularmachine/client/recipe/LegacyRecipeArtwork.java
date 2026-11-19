package com.lw.modularmachine.client.recipe;

import com.breakinblocks.neovitae.common.recipe.meteor.MeteorRecipeHelper;
import com.leclowndu93150.thaumaturge.api.aspect.AspectKnowledge;
import com.leclowndu93150.thaumaturge.api.client.AspectRendering;
import com.lw.modularmachine.api.recipe.MeteorRecipeData;
import com.lw.modularmachine.integration.astral.StarlightHatch;
import com.lw.modularmachine.integration.thaumaturge.EssentiaHatch;
import com.lw.modularmachine.util.Mods;
import com.mojang.blaze3d.systems.RenderSystem;
import de.ellpeck.naturesaura.api.NaturesAuraAPI;
import de.ellpeck.naturesaura.api.aura.type.IAuraType;
import hellfirepvp.astralsorcery.client.util.Blending;
import hellfirepvp.astralsorcery.client.util.RenderConstellationUtil;
import hellfirepvp.astralsorcery.common.constellation.BaseConstellation;
import hellfirepvp.astralsorcery.common.research.ResearchManager;
import hellfirepvp.astralsorcery.common.util.data.ColorWrapper;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class LegacyRecipeArtwork {
  private static final ResourceLocation AURA_BOTTLE = LegacyRecipeResources
      .texture(Mods.NATURES_AURA, "item/aura_bottle");
  private static final ResourceLocation AURA_OVERLAY = LegacyRecipeResources
      .texture(Mods.NATURES_AURA, "item/aura_bottle_overlay");
  private static final ResourceLocation STAR = LegacyRecipeResources.texture(Mods.ASTRAL_SORCERY, "environment/star_1");
  private static final ResourceLocation BLOOD_ORB = LegacyRecipeResources
      .texture(Mods.NEO_VITAE, "item/blood_orb_weak");
  private static final ItemStack BOTTLE = new ItemStack(Items.GLASS_BOTTLE);
  private static final ItemStack NETHER_STAR = new ItemStack(Items.NETHER_STAR);
  private static final ItemStack WILL_ICON = new ItemStack(Items.AMETHYST_SHARD);
  private static final ItemStack LIFE_ESSENCE_ICON = new ItemStack(Items.REDSTONE);
  private static final List<BackgroundStar> SKY = backgroundStars();

  private LegacyRecipeArtwork() {
  }

  static LegacyRecipeResources.Artwork aura(String kind, String amount) {
    var id = kind.isEmpty() ? null : ResourceLocation.tryParse(kind);
    return (graphics, x, y, tick) -> {
      if (Mods.NATURES_AURA.isLoaded()) {
        LegacyRecipeResources.drawIcon(graphics, AURA_BOTTLE, x, y);
        LegacyRecipeResources.tint(graphics, Aura.color(id));
        LegacyRecipeResources.drawIcon(graphics, AURA_OVERLAY, x, y);
        graphics.setColor(1, 1, 1, 1);
      } else {
        graphics.renderItem(BOTTLE, x, y);
      }
      LegacyRecipeResources.countLabel(graphics, x, y, amount);
    };
  }

  static LegacyRecipeResources.Artwork starlight() {
    var stack = BuiltInRegistries.ITEM
        .getOptional(ResourceLocation.fromNamespaceAndPath(Mods.ASTRAL_SORCERY.modid, "shifting_star"))
        .map(ItemStack::new).orElse(NETHER_STAR);
    return (graphics, x, y, tick) -> graphics.renderItem(stack, x, y);
  }

  static LegacyRecipeResources.Artwork will(String kind, String amount) {
    var texture = LegacyRecipeResources.texture(Mods.NEO_VITAE, "item/spiritus_gem_grand_" + kind);
    return (graphics, x, y, tick) -> {
      if (Mods.NEO_VITAE.isLoaded()) {
        LegacyRecipeResources.drawIcon(graphics, texture, x, y);
      } else {
        graphics.renderItem(WILL_ICON, x, y);
      }
      LegacyRecipeResources.countLabel(graphics, x, y, amount);
    };
  }

  static LegacyRecipeResources.Artwork lifeEssence() {
    return (graphics, x, y, tick) -> {
      if (Mods.NEO_VITAE.isLoaded()) {
        LegacyRecipeResources.drawIcon(graphics, BLOOD_ORB, x, y);
      } else {
        graphics.renderItem(LIFE_ESSENCE_ICON, x, y);
      }
    };
  }

  static void essentia(GuiGraphics graphics, ResourceLocation id, int x, int y) {
    if (Mods.THAUMATURGE.isLoaded()) {
      Essentia.draw(graphics, id, x, y);
    } else {
      graphics.renderItem(BOTTLE, x, y);
    }
  }

  static Component aspectName(ResourceLocation id) {
    return Component.translatableWithFallback(id.toLanguageKey("aspect"), id.toString());
  }

  static void constellation(GuiGraphics graphics, int x, int y, double tick, ResourceLocation key) {
    graphics.fill(x, y, x + 58, y + 58, 0xFF000000);
    if (Mods.ASTRAL_SORCERY.isLoaded()) {
      graphics.flush();
      RenderSystem.enableBlend();
      Blending.DEFAULT.apply();
      try {
        for (var point : SKY) {
          float brightness = (float) (.6 + .3 * Math.sin(tick / point.period + point.x));
          graphics.setColor(brightness, brightness, brightness, brightness);
          graphics.blit(STAR, x + point.x, y + point.y, 5, 5, 0, 0, 32, 32, 32, 32);
        }
        graphics.setColor(1, 1, 1, 1);
        Astral.draw(graphics, x, y, tick, key);
      } finally {
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
      }
    }
  }

  static Component constellationName(ResourceLocation key) {
    return Mods.ASTRAL_SORCERY.isLoaded() ? Astral.name(key) : LegacyRecipeResources.tip("constellation_unknown");
  }

  static List<Component> meteorTooltip(MeteorRecipeData.Launch launch) {
    var stack = BuiltInRegistries.ITEM.getOptional(launch.catalyst()).map(ItemStack::new).orElse(ItemStack.EMPTY);
    var result = new ArrayList<Component>();
    result.add(
        LegacyRecipeResources.tip(
            "meteor_output",
            stack.isEmpty() ? launch.catalyst().toString() : stack.getHoverName(),
            launch.count()));
    if (Mods.NEO_VITAE.isLoaded()) {
      Meteor.describe(result, stack);
    }
    return List.copyOf(result);
  }

  private record BackgroundStar(int x, int y, int period) {
  }

  private static List<BackgroundStar> backgroundStars() {
    var random = new Random(0x889582997FF29A92L);
    var stars = new ArrayList<BackgroundStar>();
    for (int index = 0; index < 16; index++) {
      stars.add(new BackgroundStar(random.nextInt(54), random.nextInt(54), 10 + random.nextInt(20)));
    }
    return List.copyOf(stars);
  }

  private static final class Aura {
    private static int color(ResourceLocation id) {
      var level = Minecraft.getInstance().level;
      var type = id != null ? NaturesAuraAPI.AURA_TYPES.get(id) : level == null ? null : IAuraType.forLevel(level);
      return (type == null ? NaturesAuraAPI.TYPE_OTHER : type).getColor();
    }
  }

  private static final class Essentia {
    private static void draw(GuiGraphics graphics, ResourceLocation id, int x, int y) {
      var minecraft = Minecraft.getInstance();
      var level = minecraft.level;
      var holder = level == null ? null : EssentiaHatch.resolveAspect(level.registryAccess(), id);
      if (holder == null) {
        AspectRendering.renderMissingGui(graphics, x, y);
      } else {
        AspectRendering.renderGui(graphics, minecraft.font, x, y, holder, 0, AspectKnowledge.KNOWN);
      }
    }
  }

  private static final class Astral {
    private static BaseConstellation known(ResourceLocation id) {
      var constellation = StarlightHatch.constellation(id);
      var progress = ResearchManager.getClientProgress();
      return constellation != null
          && progress != null
          && progress.hasDiscoveredConstellation(constellation) ? constellation : null;
    }

    private static void draw(GuiGraphics graphics, int x, int y, double tick, ResourceLocation id) {
      var constellation = known(id);
      if (constellation == null) {
        return;
      }
      RenderConstellationUtil.drawConstellationUI(
          ColorWrapper.WHITE,
          constellation,
          graphics.pose(),
          x,
          y,
          58,
          58,
          2,
          () -> (float) (.8 + .2 * Math.sin(tick / 8)),
          true,
          false);
    }

    private static Component name(ResourceLocation id) {
      var constellation = known(id);
      return constellation == null ? LegacyRecipeResources.tip("constellation_unknown") : constellation.getName();
    }
  }

  private static final class Meteor {
    private static void describe(List<Component> result, ItemStack catalyst) {
      var level = Minecraft.getInstance().level;
      var recipe = level == null ? null : MeteorRecipeHelper.findRecipe(level, catalyst);
      if (recipe == null) {
        return;
      }
      result.add(LegacyRecipeResources.tip("meteor_contents"));
      var percent = NumberFormat.getPercentInstance();
      percent.setMaximumFractionDigits(2);
      int omitted = 0;
      for (var layer : recipe.getLayerList()) {
        var weighted = layer.getWeightList();
        if (result.size() >= 14) {
          omitted += 1 + weighted.size();
          continue;
        }
        result.add(LegacyRecipeResources.tip("meteor_layer", layer.getLayerRadius(), layer.getFillBlock().getEntry()));
        long totalWeight = Math.max(
            layer.getMinWeight(),
            weighted.stream().mapToLong(pair -> pair.getRight()).sum() + layer.getAdditionalTotalWeight());
        for (var pair : weighted) {
          if (result.size() >= 14) {
            omitted++;
          } else if (totalWeight > 0 && pair.getRight() > 0) {
            result.add(
                LegacyRecipeResources.tip(
                    "meteor_weight",
                    pair.getLeft().getEntry(),
                    percent.format(pair.getRight() / (double) totalWeight)));
          }
        }
      }
      if (omitted > 0) {
        result.add(LegacyRecipeResources.tip("meteor_more", omitted));
      }
    }
  }
}
