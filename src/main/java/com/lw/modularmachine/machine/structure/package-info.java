/**
 * Internal definition parsing, immutable compilation and structure proofs.
 * StructureService owns catalog/registration publication; StructureLevelState
 * owns world indices; the scheduler and change router share those indices.
 * Registration owns final proof, interfaces and snapshot work. Pure workers
 * retain their permits until completion and never receive world objects.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.structure;

import org.jetbrains.annotations.ApiStatus;
