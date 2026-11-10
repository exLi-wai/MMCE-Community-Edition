package com.lw.modularmachine.api.assembly;

import java.util.List;

/** One bounded page from the immutable layout identified by {@code request}. */
public record AssemblyPage(long request, int start, int totalParts, List<AssemblyPart> parts) {
  public AssemblyPage {
    parts = List.copyOf(parts);
    if (request < 0
        || start < 0
        || totalParts < 0
        || start > totalParts
        || parts.size() > MachineAssemblyApi.MAX_PAGE_PARTS
        || (long) start + parts.size() > totalParts) {
      throw new IllegalArgumentException("Invalid assembly page");
    }
  }
}
