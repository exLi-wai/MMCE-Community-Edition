package com.lw.modularmachine.network;

import com.lw.modularmachine.api.upgrade.UpgradeDescriptions;
import com.lw.modularmachine.machine.upgrade.UpgradeDescriptionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Bounded pure tooltip requests; the server owns and executes all script
 * descriptions.
 */
@EventBusSubscriber(modid = "mmcece")
public final class UpgradeDescriptionPayloads {
  public static final CustomPacketPayload.Type<Request> REQUEST = new CustomPacketPayload.Type<>(
      ResourceLocation.parse("mmcece:upgrade_tooltip_request"));
  public static final CustomPacketPayload.Type<Response> RESPONSE = new CustomPacketPayload.Type<>(
      ResourceLocation.parse("mmcece:upgrade_tooltip_response"));
  private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
  private static volatile Consumer<Response> receiver = ignored -> {
  };

  public record Request(long sequence, ItemStack stack) implements CustomPacketPayload {
    public Request {
      stack = stack.copy();
    }
    @Override
    public ItemStack stack() {
      return stack.copy();
    }
    @Override
    public Type<Request> type() {
      return REQUEST;
    }
  }

  public record Response(long sequence, List<String> lines) implements CustomPacketPayload {
    public Response {
      lines = UpgradeDescriptions.validate(lines);
    }
    @Override
    public Type<Response> type() {
      return RESPONSE;
    }
  }

  private UpgradeDescriptionPayloads() {
  }

  public static void setReceiver(Consumer<Response> next) {
    receiver = Objects.requireNonNull(next, "receiver");
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1").playToServer(REQUEST, StreamCodec.of((RegistryFriendlyByteBuf buffer, Request request) -> {
      buffer.writeVarLong(request.sequence());
      ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, request.stack());
    }, buffer -> new Request(buffer.readVarLong(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer))),
        (request, context) -> context.enqueueWork(() -> {
          if (!(context.player() instanceof ServerPlayer player) || request.stack().isEmpty()) {
            return;
          }
          long now = player.serverLevel().getGameTime();
          var last = LAST_REQUEST.get(player);
          if (last != null && now >= last && now - last < 5) {
            return;
          }
          LAST_REQUEST.put(player, now);
          List<String> lines;
          try {
            lines = UpgradeDescriptionService.describe(request.stack(), false, null, null);
          } catch (RuntimeException invalid) {
            lines = List.of();
          }
          PacketDistributor.sendToPlayer(player, new Response(request.sequence(), lines));
        }));
    event.registrar("1").playToClient(
        RESPONSE,
        StreamCodec.of(UpgradeDescriptionPayloads::write, UpgradeDescriptionPayloads::read),
        (response, context) -> context.enqueueWork(() -> receiver.accept(response)));
  }

  private static void write(RegistryFriendlyByteBuf buffer, Response response) {
    buffer.writeVarLong(response.sequence());
    buffer.writeVarInt(response.lines().size());
    for (var line : response.lines()) {
      buffer.writeUtf(line, UpgradeDescriptions.MAX_LINE_LENGTH);
    }
  }

  private static Response read(RegistryFriendlyByteBuf buffer) {
    long sequence = buffer.readVarLong();
    int size = buffer.readVarInt();
    if (size < 0 || size > UpgradeDescriptions.MAX_LINES) {
      throw new IllegalArgumentException("Too many upgrade tooltip lines");
    }
    var lines = new ArrayList<String>();
    for (int i = 0; i < size; i++) {
      lines.add(buffer.readUtf(UpgradeDescriptions.MAX_LINE_LENGTH));
    }
    return new Response(sequence, lines);
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    LAST_REQUEST.clear();
  }
}
