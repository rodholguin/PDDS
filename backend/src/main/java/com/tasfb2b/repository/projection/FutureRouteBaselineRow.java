package com.tasfb2b.repository.projection;

public interface FutureRouteBaselineRow {
    String getOriginIcao();

    String getDestinationIcao();

    Integer getIsoDow();

    Long getShipmentCount();

    Double getAvgLuggage();
}
