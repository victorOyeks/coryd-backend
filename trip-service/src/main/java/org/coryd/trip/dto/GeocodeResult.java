package org.coryd.trip.dto;

public record GeocodeResult(String formattedAddress, double lat, double lng) {
}
