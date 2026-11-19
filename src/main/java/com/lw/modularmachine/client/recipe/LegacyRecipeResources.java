package com.lw.modularmachine.client.recipe;

import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRequirement;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout.Kind;
import com.lw.modularmachine.client.recipe.MachineRecipeLayout.Part;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.integration.pneumaticcraft.CompressedAirGauge;
import com.lw.modularmachine.machine.EmberHatchTier;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.util.Mods;
import com.mojang.blaze3d.systems.RenderSystem;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import java.awt.Color;

final class LegacyRecipeResources {
  private static final String KEY = "jei.mmcece.machine_item.";
  private static final String ASSET = "textures/gui/legacy_recipe/";
  private static final ResourceLocation ENVIRONMENT = asset("recipes_ui");
  private static final ResourceLocation MANA = asset("widgets");
  private static final ResourceLocation PORTAL = ResourceLocation.withDefaultNamespace("block/nether_portal");
  private static final ItemStack CLOCK = new ItemStack(Items.CLOCK);
  private static final ItemStack COMPASS = new ItemStack(Items.COMPASS);
  private static final Map<Kind, ResourceLocation> ICONS = Map.of(
      Kind.LASER,
      asset("jei_laser"),
      Kind.HEAT,
      asset("jei_heat"),
      Kind.VIS,
      asset("jei_vis"),
      Kind.FLUX,
      asset("jei_flux"),
      Kind.METEOR,
      asset("jei_meteor"));

  @FunctionalInterface
  interface Artwork {
    void draw(GuiGraphics graphics, int x, int y, double tick);
  }

  record Entry(Artwork artwork, Supplier<List<Component>> tooltip) {
  }

  private final Map<Kind, List<Entry>> inputs = new EnumMap<>(Kind.class);
  private final Map<Kind, List<Entry>> outputs = new EnumMap<>(Kind.class);

  LegacyRecipeResources(MachineRecipe recipe) {
    var resources = recipe.resourceData();
    var environment = resources.get(RecipeResourceDefinitions.ENVIRONMENT);
    environment.requirements().forEach(condition -> add(Kind.ENVIRONMENT, true, environment(condition)));
    long laser = resources.get(RecipeResourceDefinitions.LASER).input();
    if (laser > 0) {
      add(Kind.LASER, true, icon(Kind.LASER), tip("laser_input", number(laser, 0)));
    }
    var ember = resources.get(RecipeResourceDefinitions.EMBER);
    if (ember.micros() > 0) {
      add(Kind.EMBER, true, ember(ember.micros()));
    }
    amounts(Kind.MANA, "mana", resources.get(RecipeResourceDefinitions.MANA), LegacyRecipeResources::mana);
    amounts(Kind.HEAT, "heat", resources.get(RecipeResourceDefinitions.HEAT), amount -> icon(Kind.HEAT));
    amounts(
        Kind.LIFE_ESSENCE,
        "life_essence",
        resources.get(RecipeResourceDefinitions.LIFE_ESSENCE),
        amount -> LegacyRecipeArtwork.lifeEssence());
    var aura = resources.get(RecipeResourceDefinitions.AURA);
    if (aura.input() > 0) {
      add(
          Kind.AURA,
          true,
          LegacyRecipeArtwork.aura("", number(aura.input(), 0)),
          tip("aura_input", number(aura.input(), 0)));
    }
    pool(Kind.AURA, "aura_world", resources.get(RecipeResourceDefinitions.AURA_WORLD), 0);
    pool(Kind.WILL, "will", resources.get(RecipeResourceDefinitions.WILL), 3);
    pool(Kind.VIS, "vis", resources.get(RecipeResourceDefinitions.VIS), 3);
    pool(Kind.FLUX, "flux", resources.get(RecipeResourceDefinitions.FLUX), 3);
    resources.get(RecipeResourceDefinitions.METEOR).output().ifPresent(
        launch -> add(
            Kind.METEOR,
            false,
            new Entry(icon(Kind.METEOR), () -> LegacyRecipeArtwork.meteorTooltip(launch))));
    keyed(Kind.ESSENTIA, resources.get(RecipeResourceDefinitions.ESSENTIA), 0);
    keyed(Kind.STARLIGHT, resources.get(RecipeResourceDefinitions.STARLIGHT), 3);
    resources.get(RecipeResourceDefinitions.CONSTELLATION).active().forEach(
        key -> add(
            Kind.CONSTELLATION,
            true,
            new Entry(
                (graphics, x, y, tick) -> LegacyRecipeArtwork.constellation(graphics, x, y, tick, key),
                () -> List.of(tip("constellation", LegacyRecipeArtwork.constellationName(key))))));
    var air = resources.get(RecipeResourceDefinitions.COMPRESSED_AIR);
    air.input().ifPresent(amount -> add(Kind.AIR, true, air(amount.air(), amount.pressure(), true)));
    air.output().ifPresent(amount -> add(Kind.AIR, false, air(amount.air(), 0, false)));
  }

  int count(Kind kind, boolean input) {
    return entries(kind, input).size();
  }

  @Nullable
  Entry entry(Part part) {
    var entries = entries(part.kind(), part.input());
    return part.index() < entries.size() ? entries.get(part.index()) : null;
  }

  private List<Entry> entries(Kind kind, boolean input) {
    return (input ? inputs : outputs).getOrDefault(kind, List.of());
  }

  private void add(Kind kind, boolean input, Entry entry) {
    (input ? inputs : outputs).computeIfAbsent(kind, ignored -> new ArrayList<>()).add(entry);
  }

  private void add(Kind kind, boolean input, Artwork artwork, Component tooltip) {
    add(kind, input, new Entry(artwork, () -> List.of(tooltip)));
  }

  private void amounts(Kind kind, String key, BufferedAmountRecipeData data, LongFunction<Artwork> artwork) {
    if (data.input() > 0) {
      add(kind, true, artwork.apply(data.input()), tip(key + "_input", number(data.input(), 0)));
    }
    if (data.output() > 0) {
      add(kind, false, artwork.apply(data.output()), tip(key + "_output", number(data.output(), 0)));
    }
  }

  private void pool(Kind kind, String key, WorldPoolRecipeData data, int scale) {
    data.input().ifPresent(claim -> pool(kind, key, claim, scale, true));
    data.output().ifPresent(claim -> pool(kind, key, claim, scale, false));
  }

  private void pool(Kind kind, String key, WorldPoolRecipeData.Claim claim, int scale, boolean input) {
    String amount = number(claim.amount(), scale);
    Artwork artwork = switch (kind) {
      case AURA -> LegacyRecipeArtwork.aura(claim.kind(), amount);
      case WILL -> LegacyRecipeArtwork.will(claim.kind(), amount);
      default -> icon(kind);
    };
    String limit = number(claim.limit(), scale);
    var tooltip = kind == Kind.AURA ? tip(
        key + direction(input),
        amount,
        claim.kind(),
        limit) : tip(key + direction(input), amount, claim.kind(), claim.radius(), limit);
    add(
        kind,
        input,
        new Entry(
            artwork,
            () -> kind == Kind.WILL ? List
                .of(tooltip, tip(claim.multichunk() ? "will_multichunk" : "will_single_chunk")) : List.of(tooltip)));
  }

  private void keyed(Kind kind, KeyedAmountRecipeData data, int scale) {
    for (boolean input : new boolean[]{true, false}) {
      var values = input ? data.input() : data.output();
      values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
        ResourceLocation id = entry.getKey();
        String amount = number(entry.getValue(), scale);
        if (kind == Kind.ESSENTIA) {
          add(
              kind,
              input,
              new Entry(
                  (graphics, x, y, tick) -> LegacyRecipeArtwork.essentia(graphics, id, x, y),
                  () -> List.of(tip("essentia" + direction(input), LegacyRecipeArtwork.aspectName(id), amount))));
        } else {
          add(
              kind,
              input,
              LegacyRecipeArtwork.starlight(),
              tip("starlight" + direction(input), starlightSource(id), amount));
        }
      });
    }
  }

  private static Component starlightSource(ResourceLocation id) {
    if (id.equals(KeyedAmountRecipeData.ANY_STARLIGHT)) {
      return tip("starlight_any");
    }
    return switch (id.toString()) {
      case "mmcece:sky" -> tip("starlight_sky");
      case "mmcece:unattuned" -> tip("starlight_unattuned");
      default -> Component.literal(id.toString());
    };
  }

  private static Entry environment(EnvironmentRequirement condition) {
    Component tooltip = switch (condition) {
      case EnvironmentRequirement.Dimension value -> tip("environment_dimension", joined(value.dimensions()));
      case EnvironmentRequirement.Biome value -> tip("environment_biome", joined(value.biomes()));
      case EnvironmentRequirement.Time value -> tip("environment_time", value.begin(), value.end());
      case EnvironmentRequirement.MoonPhase value -> tip(
          "environment_moonphase",
          value.moonphases().stream().map(phase -> I18n.get(KEY + "moonphase." + phase))
              .collect(Collectors.joining(", ")));
      case EnvironmentRequirement.Altitude value -> tip("environment_altitude", value.begin(), value.end());
      case EnvironmentRequirement.Weather value ->
        tip("environment_weather", tip("weather." + value.weather().getSerializedName()));
    };
    return new Entry((graphics, x, y, tick) -> {
      switch (condition) {
        case EnvironmentRequirement.Dimension ignored -> {
          var portal = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PORTAL);
          graphics.blit(x, y, 0, 16, 16, portal, 1, 1, 1, .8F);
        }
        case EnvironmentRequirement.Time ignored -> graphics.renderItem(CLOCK, x, y);
        case EnvironmentRequirement.MoonPhase value -> {
          int phase = value.moonphases().get((int) (tick / 30) % value.moonphases().size());
          environmentIcon(graphics, x, y, 106 + 16 * phase, 0);
        }
        case EnvironmentRequirement.Weather value ->
          environmentIcon(graphics, x, y, 106 + 16 * value.weather().ordinal(), 16);
        case EnvironmentRequirement.Altitude ignored -> environmentIcon(graphics, x, y, 170, 16);
        case EnvironmentRequirement.Biome ignored -> environmentIcon(graphics, x, y, 186, 16);
      }
    }, () -> List.of(tooltip));
  }

  private static void environmentIcon(GuiGraphics graphics, int x, int y, int u, int v) {
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    graphics.setColor(1, 1, 1, .8F);
    graphics.blit(ENVIRONMENT, x, y, u, v, 16, 16, 256, 257);
    graphics.setColor(1, 1, 1, 1);
    RenderSystem.disableBlend();
  }

  private static Entry ember(long micros) {
    double amount = micros / 1_000_000.0;
    EmberHatchTier tier = null;
    int capacity = 0;
    for (var candidate : EmberHatchTier.values()) {
      int configured = MachineConfig.emberInputCapacity(candidate);
      capacity = Math.max(capacity, configured);
      if (configured * 1_000_000L >= micros) {
        tier = candidate;
        capacity = configured;
        break;
      }
    }
    int filled = Math.clamp((int) (100 * amount / Math.max(800, capacity)), 1, 100);
    int empty = 100 - filled;
    var minimum = tier == null ? tip(
        "ember_no_hatch") : tip("ember_hatch", Component.translatable("block.mmcece.ember_input_hatch_" + tier.path()));
    String quantity = number(micros, 6);
    return new Entry((graphics, x, y, tick) -> {
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      graphics.setColor(1, 1, 1, .7F);
      graphics.blit(ENVIRONMENT, x, y, 8, 0, 5, 102, 256, 257);
      graphics.blit(ENVIRONMENT, x + 1, y + 1, 13, 0, 3, 100, 256, 257);
      graphics.blit(ENVIRONMENT, x + 1, y + 1 + empty, 16 + (int) (tick * 3 % 87), empty, 3, filled, 256, 257);
      graphics.setColor(1, 1, 1, 1);
      RenderSystem.disableBlend();
    }, () -> List.of(tip("ember_input", quantity), minimum));
  }

  private static Artwork mana(long amount) {
    int fill = Math.clamp((int) (amount / 10_000D * 61), 1, 61);
    int empty = 61 - fill;
    return (graphics, x, y, tick) -> {
      graphics.blit(MANA, x, y, 0, 0, 5, 63, 256, 256);
      graphics.blit(MANA, x + 1, y + 1, 5, 0, 3, 61, 256, 256);
      int color = Color.HSBtoRGB(.55F, (float) Math.min(1, Math.sin(tick / 4) * .5 + 1), 1);
      tint(graphics, color);
      graphics.blit(MANA, x + 1, y + 1 + empty, 5, empty, 3, fill, 256, 256);
      graphics.setColor(1, 1, 1, 1);
    };
  }

  private static Entry air(long amount, float pressure, boolean input) {
    String quantity = number(amount, 0);
    return new Entry((graphics, x, y, tick) -> {
      if (Mods.PNEUMATIC_CRAFT.isLoaded()) {
        CompressedAirGauge
            .renderRecipe(graphics, Minecraft.getInstance().font, pressure, x + 25, y + 25, (float) (tick % 60 / 60));
      } else {
        graphics.renderItem(COMPASS, x + 17, y + 17);
      }
    }, () -> List.of(input ? tip("compressed_air_input", quantity, pressure) : tip("compressed_air_output", quantity)));
  }

  static Component tip(String key, Object... arguments) {
    return Component.translatable(KEY + key, arguments);
  }

  static String number(long value, int scale) {
    var format = NumberFormat.getNumberInstance();
    format.setMaximumFractionDigits(scale);
    return format.format(BigDecimal.valueOf(value, scale));
  }

  private static String joined(List<?> values) {
    return values.stream().map(Object::toString).collect(Collectors.joining(", "));
  }

  private static String direction(boolean input) {
    return input ? "_input" : "_output";
  }

  private static ResourceLocation asset(String name) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", ASSET + name + ".png");
  }

  static ResourceLocation texture(Mods mod, String path) {
    return ResourceLocation.fromNamespaceAndPath(mod.modid, "textures/" + path + ".png");
  }

  private static Artwork icon(Kind kind) {
    var texture = ICONS.get(kind);
    return kind == Kind.HEAT ? (graphics, x, y, tick) -> graphics
        .blit(texture, x, y, 16, 16, 32, 32, 64, 64, 128, 128) : sprite(texture);
  }

  private static Artwork sprite(ResourceLocation texture) {
    return (graphics, x, y, tick) -> drawIcon(graphics, texture, x, y);
  }

  static void drawIcon(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
    graphics.blit(texture, x, y, 0, 0, 16, 16, 16, 16);
  }

  static void tint(GuiGraphics graphics, int rgb) {
    graphics.setColor((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1);
  }

  static void countLabel(GuiGraphics graphics, int x, int y, String amount) {
    var font = Minecraft.getInstance().font;
    int width = font.width(amount);
    float scale = Math.min(1, 16F / Math.max(1, width));
    graphics.pose().pushPose();
    try {
      graphics.pose().translate(x + 16, y + 16, 200);
      graphics.pose().scale(scale, scale, 1);
      graphics.drawString(font, amount, -width, -8, 0xFFFFFFFF, true);
    } finally {
      graphics.pose().popPose();
    }
  }
}
