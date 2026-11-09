package com.lw.modularmachine.machine.selection;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.ApiStatus;

/** Internal selection messages, resolved in the receiving client's language. */
@ApiStatus.Internal
final class RangedSelectionMessages {
  private RangedSelectionMessages() {
  }

  static MutableComponent text(String key, Object... arguments) {
    return Component.translatable("message.mmcece.selection." + key, arguments);
  }

  static IllegalArgumentException invalid(String key, Object... arguments) {
    return new Failure(key, arguments);
  }

  static Component detail(Throwable failure) {
    while (true) {
      if (failure instanceof Failure localized) {
        return text(localized.key, localized.arguments);
      }
      if (failure.getCause() == null) {
        break;
      }
      failure = failure.getCause();
    }
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      message = failure.getClass().getSimpleName();
    }
    return Component.literal(message.length() > 1024 ? message.substring(0, 1024) : message);
  }

  static Component saved(MachineDefinitionFileStore.SaveResult saved, DynamicDefinitionEditor.Result result) {
    var message = text(
        "saved_detail",
        saved.file().toString(),
        result.mainParts(),
        result.dynamicParts(),
        result.deduplicated());
    message.append("\n").append(text("excluded_controller", result.excludedController()));
    message.append("\n").append(text("source_hash", saved.sourceHash()));
    message.append("\n").append(text("target_hash", saved.targetHash()));
    if (saved.backup() != null) {
      message.append("\n").append(text("backup", saved.backup().toString()));
    }
    return message;
  }

  /** Only the translation key and detached arguments cross a worker boundary. */
  private static final class Failure extends IllegalArgumentException {
    private final String key;
    private final Object[] arguments;

    private Failure(String key, Object[] arguments) {
      super("message.mmcece.selection." + key);
      this.key = key;
      this.arguments = arguments.clone();
    }
  }
}
