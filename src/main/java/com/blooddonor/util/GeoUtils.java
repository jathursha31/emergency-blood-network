package com.blooddonor.util;

/**
 * Distance helpers. We first narrow candidates in SQL with a lat/lng bounding box
 * (cheap, uses the index), then compute the exact great-circle distance here.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtils() {
    }

    /** Haversine distance in kilometres. */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Rectangle that fully contains the circle of radiusKm around the point. */
    public static BoundingBox boundingBox(double lat, double lng, double radiusKm) {
        double latDelta = Math.toDegrees(radiusKm / EARTH_RADIUS_KM);
        double cosLat = Math.max(Math.cos(Math.toRadians(lat)), 0.01);
        double lngDelta = Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cosLat));
        return new BoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
    }

    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
    }
}
