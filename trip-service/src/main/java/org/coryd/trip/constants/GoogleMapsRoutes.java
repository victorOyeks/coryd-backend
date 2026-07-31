package org.coryd.trip.constants;

/** URL templates for Google Maps Platform endpoints, keyed against {@link org.coryd.trip.config.MapsClientConfig}'s base-url. */
public final class GoogleMapsRoutes {

    private GoogleMapsRoutes() {
    }

    public static final String AUTOCOMPLETE =
            "/place/autocomplete/json?input={input}&components=country:ng&language=en&key={key}";

    public static final String PLACE_DETAILS =
            "/place/details/json?place_id={placeId}&fields=name,geometry,formatted_address&key={key}";

    public static final String REVERSE_GEOCODE =
            "/geocode/json?latlng={latlng}&key={key}";

    public static final String DISTANCE_MATRIX =
            "/distancematrix/json?origins={origins}&destinations={destinations}&mode=driving&key={key}";

    public static final String DIRECTIONS =
            "/directions/json?origin={origin}&destination={destination}&mode=driving&key={key}";
}
