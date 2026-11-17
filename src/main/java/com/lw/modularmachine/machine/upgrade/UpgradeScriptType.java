package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeDescriptions;
import com.lw.modularmachine.api.upgrade.UpgradeType;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** Captured script type and its unbound effect template. */
@ApiStatus.Internal
public record UpgradeScriptType(
    UpgradeType type,
    UpgradeDefinition template,
    UpgradeDescriptions.Handler description,
    UpgradeDescriptions.Handler busDescription) {
  public UpgradeScriptType {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(template, "template");
    if (!template.effect().id().equals(type.id())) {
      throw new IllegalArgumentException("Upgrade type and effect identity disagree");
    }
  }
}
