package org.coryd.trip.event;

/** In-process signal that a route's search-visible state changed (posted, seat count, FULL/reopened,
 * cancelled, started) - LiveSearchPushService listens for this to re-push live search results. */
public record RouteSearchRelevantChangeEvent(String routePostId) {
}
