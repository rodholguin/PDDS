package com.tasfb2b.repository.projection;

import com.tasfb2b.model.ShipmentStatus;
import java.time.LocalDateTime;

public interface ShipmentSummaryRow {
    Long getId();
    String getShipmentCode();
    String getAirlineName();
    String getOriginIcao();
    Double getOriginLatitude();
    Double getOriginLongitude();
    String getDestinationIcao();
    Double getDestinationLatitude();
    Double getDestinationLongitude();
    ShipmentStatus getStatus();
    Double getProgressPercentage();
    LocalDateTime getDeadline();
}
