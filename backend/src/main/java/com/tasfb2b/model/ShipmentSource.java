package com.tasfb2b.model;

/**
 * Origen del envío. Separa la operación EN VIVO (escenario DAY_TO_DAY) de la data histórica del
 * dataset que usan COLLAPSE_TEST y PERIOD_SIMULATION.
 *
 * <p>DAY_TO_DAY (producto operativo real, reloj = hora actual) opera SOLO envíos {@link #LIVE};
 * los {@link #HISTORICAL} (los ~9.5M del dataset) quedan intactos e invisibles para la operación
 * día a día. Los otros escenarios siguen usando los HISTORICAL como antes.
 */
public enum ShipmentSource {

    /** Envío del dataset histórico / importado (datos/envios). Lo usan COLLAPSE_TEST y PERIOD_SIMULATION. */
    HISTORICAL,

    /** Envío registrado EN VIVO (data-entry manual o carga txt de prueba) en la operación DAY_TO_DAY. */
    LIVE
}
