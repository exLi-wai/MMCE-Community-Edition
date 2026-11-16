package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * One captured bus interpretation; callers retain their own
 * epoch/thread/structure gates.
 */
@ApiStatus.Internal
record UpgradeSourceStamp(UpgradeBusBlockEntity bus, long revision, UpgradeCatalog catalog) {
  static boolean matches(UpgradeBusOwner owner, List<UpgradeSourceStamp> sources) {
    var buses = owner.upgradeCallbackBuses();
    if (buses.size() != sources.size()) {
      return false;
    }
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index);
      if (buses.get(index) != source.bus()
          || source.bus().upgradeRevision() != source.revision()
          || source.bus().upgradeCatalog(owner) != source.catalog()) {
        return false;
      }
    }
    return true;
  }
}
