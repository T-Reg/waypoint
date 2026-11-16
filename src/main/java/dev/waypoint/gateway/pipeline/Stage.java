package dev.waypoint.gateway.pipeline;

import dev.waypoint.gateway.error.Rejection;
import java.util.Optional;

/** One pipeline step: empty means continue; a rejection short-circuits before the upstream is called. */
@FunctionalInterface
public interface Stage {

    Optional<Rejection> apply(RequestContext context);
}
