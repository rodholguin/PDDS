package com.tasfb2b.repository.projection;

public interface RouteNetworkEdgeRow {
    String getOriginIcao();

    Double getOriginLatitude();

    Double getOriginLongitude();

    String getDestinationIcao();

    Double getDestinationLatitude();

    Double getDestinationLongitude();

    Long getScheduledCount();

    Long getInFlightCount();

    Long getCancelledCount();
}
