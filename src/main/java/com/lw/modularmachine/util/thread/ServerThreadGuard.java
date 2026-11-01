package com.lw.modularmachine.util.thread;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Shared server-thread assertion with an owner-specific failure context. */
public final class ServerThreadGuard {
  private ServerThreadGuard() {
  }

  public static void require(ServerLevel level, String context) {
    Objects.requireNonNull(level, "level");
    require(level.getServer(), context);
  }

  public static void require(MinecraftServer server, String context) {
    Objects.requireNonNull(server, "server");
    if (!server.isSameThread()) {
      throw new IllegalStateException(context + " outside server thread");
    }
  }
}
