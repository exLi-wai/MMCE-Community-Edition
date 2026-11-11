package com.lw.modularmachine.util.client.search;

import com.lw.modularmachine.util.Mods;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import me.towdium.jecharacters.utils.Match;
import me.towdium.pinin.utils.Accelerator;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client name matching with optional JEC pinyin support. Prepare one
 * query per search pass. Instances and predicates must stay on the client
 * thread.
 */
@ApiStatus.Internal
public final class NameSearch {
  private final Function<String, Predicate<String>> queryFactory;

  public NameSearch() {
    queryFactory = Mods.JEC.isLoaded() ? JecMatcher.queryFactory() : query -> name -> name.contains(query);
  }

  /** Matches non-null names by literal text or the current JEC pinyin rules. */
  public Predicate<String> query(String text) {
    String query = text.strip().toLowerCase(Locale.ROOT);
    if (query.isEmpty()) {
      return name -> true;
    }
    var matcher = queryFactory.apply(query);

    return name -> matcher.test(name.toLowerCase(Locale.ROOT));
  }

  /** Optional classes are only resolved after the JEC load check. */
  private static final class JecMatcher {
    private static Function<String, Predicate<String>> queryFactory() {
      var accelerator = new Accelerator(Match.context);
      var ticket = Match.context.ticket(accelerator::reset);

      return query -> {
        ticket.renew();

        return name -> {
          if (name.contains(query)) {
            return true;
          }
          accelerator.setProvider(name);
          accelerator.search(query);

          return accelerator.contains(0, 0);
        };
      };
    }
  }
}
