package com.lw.modularmachine.client.preview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.Font;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/**
 * Retained raw length text, accepted values and validation across preview
 * replacement.
 */
@ApiStatus.Internal
final class PreviewLengthInputs {
  private final PreviewDisplayState preview;
  private final Supplier<Set<String>> retainedKeys;
  private final BiConsumer<String, Integer> changed;
  private final Map<String, LengthInput> lengthInputs = new LinkedHashMap<>();

  PreviewLengthInputs(PreviewDisplayState preview, Supplier<Set<String>> retainedKeys, BiConsumer<String, Integer> changed) {
    this.preview = preview;
    this.retainedKeys = retainedKeys;
    this.changed = changed;
  }
  List<EditBox> fields() {
    return lengthInputs.values().stream().map(input -> input.field).toList();
  }
  void retain(Set<String> keys) {
    lengthInputs.keySet().retainAll(keys);
  }
  EditBox reconcile(Font font, Component title, String key, int i, Set<String> keys) {
    var input = lengthInputs.get(key);
    if (input == null) {
      var field = new EditBox(font, 0, 0, 80, 18, title);
      field.setMaxLength(10);
      field.setFilter(value -> value.matches("[0-9]*"));
      field.setValue(Integer.toString(preview.selection.length(i)));
      input = new LengthInput(field, preview.selection.length(i));
      lengthInputs.put(key, input);
      field.setResponder(value -> editLength(key, value));
    } else if (input.valid && input.accepted != preview.selection.length(i)) {
      input.accepted = preview.selection.length(i);
      input.field.setValue(Integer.toString(input.accepted));
    }
    validateLengthInput(key, input);
    input.field.active = keys.contains(key);
    return input.field;
  }

  private static final class LengthInput {
    final EditBox field;
    int accepted;
    boolean valid = true;

    LengthInput(EditBox field, int accepted) {
      this.field = field;
      this.accepted = accepted;
    }
  }

  int initial(String key, int minimum, int maximum) {
    var input = lengthInputs.get(key);

    return input != null && input.accepted >= minimum && input.accepted <= maximum ? input.accepted : minimum;
  }

  private static int segmentIndex(MachinePreviewDefinition source, String key) {
    if (source != null) {
      for (int i = 0; i < source.segments().size(); i++) {
        if (source.segments().get(i).id().equals(key)) {
          return i;
        }
      }
    }
    return -1;
  }

  private void editLength(String key, String value) {
    var input = lengthInputs.get(key);
    var target = preview.pendingSelection == null ? preview.selection : preview.pendingSelection;
    var source = preview.pendingSelection == null ? preview.definition : preview.pendingDefinition;
    int index = segmentIndex(source, key);
    boolean valid = false;
    if (input != null && target != null && index >= 0 && retainedKeys.get().contains(key)) {
      try {
        int requested = Integer.parseInt(value);
        valid = target.setLength(index, requested);
        if (valid && input.accepted != requested) {
          input.accepted = requested;
          changed.accept(key, requested);
        }
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        // Preserve raw text and the last complete scene while editing.
      }
    }
    if (input != null) {
      input.valid = valid;
      input.field.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF7777);
    }
  }

  private void validateLengthInput(String key, LengthInput input) {
    int index = segmentIndex(preview.definition, key);
    input.valid = false;
    if (index >= 0) {
      var bounds = preview.definition.segments().get(index);
      try {
        int requested = Integer.parseInt(input.field.getValue());
        input.valid = requested == preview.selection.length(index)
            && requested >= bounds.minimum()
            && requested <= bounds.maximum();
      } catch (NumberFormatException invalid) {
        // Reattaching a field must not replace an unfinished number.
      }
    }
    input.field.setTextColor(input.valid ? 0xFFE0E0E0 : 0xFFFF7777);
  }

  int desiredLength(int index) {
    var input = lengthInputs.get(preview.definition.segments().get(index).id());
    return input == null ? preview.selection.length(index) : input.accepted;
  }

  boolean failed() {
    return preview.selection != null && preview.selection.failed()
        || preview.pendingSelection != null && preview.pendingSelection.failed()
        || lengthInputs.entrySet().stream().anyMatch(
            entry -> !entry.getValue().valid
                && (segmentIndex(preview.definition, entry.getKey()) >= 0
                    || segmentIndex(preview.pendingDefinition, entry.getKey()) >= 0));
  }

}
