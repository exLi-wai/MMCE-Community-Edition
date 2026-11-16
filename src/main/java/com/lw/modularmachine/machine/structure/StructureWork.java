package com.lw.modularmachine.machine.structure;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.structure.StructureService.Registration;

@ApiStatus.Internal
sealed interface StructureWork permits StructureAnchor, Registration {
}
