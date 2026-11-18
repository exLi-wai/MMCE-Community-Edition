package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRequirement;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedStock;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyAddonDeclarationsTest {
  @Test
  void worldPoolFreezesAmountWithoutScalingCoverageOrLimits() {
    var codec = WorldPoolRecipeData.codec("will", true).codec();
    var json = JsonParser.parseString("""
        {"will_input":{"type":"raw","amount":500,"multichunk":true,"radius":0,"limit":700}}
        """);
    var data = codec.parse(JsonOps.INSTANCE, json).getOrThrow().scaled(8);
    assertEquals(new WorldPoolRecipeData.Claim("raw", 4_000, true, 0, 700), data.input().orElseThrow());
    assertEquals(
        data,
        codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow()).getOrThrow());
  }

  @Test
  void keyedResourcesKeepExactKeysAndShareOnlyCapacity() {
    var codec = KeyedAmountRecipeData.codec("essentia").codec();
    var data = codec.parse(JsonOps.INSTANCE, JsonParser.parseString("""
        {"essentia_input":{"thaumaturge:aer":4,"thaumaturge:ignis":9},"essentia_output":{"thaumaturge:aer":1}}
        """)).getOrThrow();
    var first = ResourceLocation.parse("thaumaturge:aer");
    var second = ResourceLocation.parse("thaumaturge:ignis");
    var stock = new KeyedStock(12);
    assertEquals(8L, data.scaled(2).input().get(first));
    assertEquals(4, stock.move(first, data.input().get(first), false));
    assertEquals(8, stock.move(second, data.input().get(second), true));
    assertEquals(0, stock.amount(second));
    var lease = stock.acquire(first, -4);
    assertEquals(12, stock.space());
    lease.resolve(false);
    stock.acquire(second, 8).resolve(true);
    var restored = new KeyedStock(12);
    restored.load(stock.save());
    assertEquals(4, restored.amount(first));
    assertEquals(8, restored.amount(second));
    assertEquals(0, restored.space());
  }

  @Test
  void scalarPortsKeepBothDirectionsAndRejectInexactQuantities() {
    var codec = BufferedAmountRecipeData.codec("mana_input", "mana_output").codec();
    var raw = JsonParser.parseString("{\"mana_input\":5,\"mana_output\":9223372036854775807}");
    var data = codec.parse(JsonOps.INSTANCE, raw).getOrThrow();
    assertEquals(new BufferedAmountRecipeData(5, Long.MAX_VALUE), data);
    assertEquals(
        data,
        codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow()).getOrThrow());
    assertTrue(codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"mana_output\":1.5}")).error().isPresent());
    assertThrows(ArithmeticException.class, () -> data.scaled(2));
    assertTrue(
        BufferedAmountRecipeData.inputCodec("aura_input").codec().encodeStart(NbtOps.INSTANCE, data).error()
            .isPresent());
  }

  @Test
  void emberRoundTripsAnExactParallelChargeAndRejectsSubMicros() {
    var codec = EmberRecipeData.CODEC.codec();
    var unit = codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"ember_input\":12.345679}")).getOrThrow();
    assertEquals(12_345_679L, unit.micros());
    var frozen = new EmberRecipeData(Math.multiplyExact(unit.micros(), 2_048));
    var nbt = codec.encodeStart(NbtOps.INSTANCE, frozen).getOrThrow();
    assertEquals(frozen, codec.parse(NbtOps.INSTANCE, nbt).getOrThrow());
    assertTrue(
        codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"ember_input\":0.0000001}")).error().isPresent());
  }

  @Test
  void sixEnvironmentConditionsRoundTripThroughFrozenNbt() {
    var json = JsonParser.parseString("""
        {"environment":[
          {"type":"dimension","dimensions":["minecraft:overworld"]},
          {"type":"biome","biomes":["minecraft:plains"]},
          {"type":"time","begin":23000,"end":1000},
          {"type":"moonphase","moonphases":[0,7]},
          {"type":"altitude","begin":64,"end":128},
          {"type":"weather","weather":"sunny"}
        ]}
        """);
    var codec = EnvironmentRecipeData.CODEC.codec();
    var data = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
    assertEquals(6, data.requirements().size());
    var nbt = codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
    assertEquals(data, codec.parse(NbtOps.INSTANCE, nbt).getOrThrow());
  }

  @Test
  void invertedDayTimeWrapsAtMidnight() {
    var lateNight = new EnvironmentRequirement.Time(23000, 1000);
    assertTrue(lateNight.contains(23000));
    assertTrue(lateNight.contains(25000));
    assertFalse(lateNight.contains(25001));
    assertFalse(lateNight.contains(12000));
  }

  @Test
  void laserCodecKeepsLongCountsAndRejectsInvalidInput() {
    var codec = LaserRecipeData.CODEC.codec();
    var raw = JsonParser.parseString("{\"laser_input\":9223372036854775807}");
    assertEquals(Long.MAX_VALUE, codec.parse(JsonOps.INSTANCE, raw).getOrThrow().input());
    assertTrue(codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"laser_input\":-1}")).error().isPresent());
    assertTrue(codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"laser_input\":1.5}")).error().isPresent());
  }
}
