-- =============================================================================
-- Tasf B2B – Datos seed iniciales
-- Se ejecuta UNA vez al arrancar si las tablas están vacías.
-- Requiere: spring.jpa.defer-datasource-initialization=true
-- =============================================================================

-- ── Aeropuertos ───────────────────────────────────────────────────────────────
--   Capacidades aleatorias entre 500 y 800 (fijadas para reproducibilidad).

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'JFK',  'Nueva York',       'Estados Unidos', 'AMERICA', 720, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'JFK');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'LAX',  'Los Ángeles',      'Estados Unidos', 'AMERICA', 680, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'LAX');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'BOG',  'Bogotá',           'Colombia',       'AMERICA', 550, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'BOG');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'GRU',  'São Paulo',        'Brasil',         'AMERICA', 630, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'GRU');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'EZE',  'Buenos Aires',     'Argentina',      'AMERICA', 510, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'EZE');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'LIM',  'Lima',             'Perú',           'AMERICA', 580, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'LIM');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'LHR',  'Londres',          'Reino Unido',    'EUROPE',  780, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'LHR');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'CDG',  'París',            'Francia',        'EUROPE',  760, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'CDG');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'MAD',  'Madrid',           'España',         'EUROPE',  640, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'MAD');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'FRA',  'Fráncfort',        'Alemania',       'EUROPE',  700, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'FRA');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'FCO',  'Roma',             'Italia',         'EUROPE',  590, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'FCO');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'NRT',  'Tokio',            'Japón',          'ASIA',    730, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'NRT');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'PEK',  'Pekín',            'China',          'ASIA',    800, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'PEK');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'DXB',  'Dubái',            'Emiratos Árabes','ASIA',    770, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'DXB');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'BOM',  'Bombay',           'India',          'ASIA',    620, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'BOM');

INSERT INTO airport (icao_code, city, country, continent, max_storage_capacity, current_storage_load)
SELECT 'SIN',  'Singapur',         'Singapur',       'ASIA',    680, 0 WHERE NOT EXISTS (SELECT 1 FROM airport WHERE icao_code = 'SIN');


-- ── Vuelos ─────────────────────────────────────────────────────────────────────
--   transit_time_days: 0.5 intra / 1.0 inter
--   scheduled_arrival = scheduled_departure + transit

-- ── Intra-América ─────────────────────────────────────────────────────────────

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-AM-001',
    (SELECT id FROM airport WHERE icao_code = 'JFK'),
    (SELECT id FROM airport WHERE icao_code = 'BOG'),
    false,
    200, 0,
    NOW() + INTERVAL '2 hours',
    NOW() + INTERVAL '14 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-AM-001');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-AM-002',
    (SELECT id FROM airport WHERE icao_code = 'LAX'),
    (SELECT id FROM airport WHERE icao_code = 'LIM'),
    false,
    180, 0,
    NOW() + INTERVAL '4 hours',
    NOW() + INTERVAL '16 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-AM-002');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-AM-003',
    (SELECT id FROM airport WHERE icao_code = 'GRU'),
    (SELECT id FROM airport WHERE icao_code = 'EZE'),
    false,
    160, 0,
    NOW() + INTERVAL '6 hours',
    NOW() + INTERVAL '18 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-AM-003');

-- ── Intra-Europa ──────────────────────────────────────────────────────────────

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-EU-001',
    (SELECT id FROM airport WHERE icao_code = 'LHR'),
    (SELECT id FROM airport WHERE icao_code = 'CDG'),
    false,
    220, 0,
    NOW() + INTERVAL '3 hours',
    NOW() + INTERVAL '15 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-EU-001');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-EU-002',
    (SELECT id FROM airport WHERE icao_code = 'MAD'),
    (SELECT id FROM airport WHERE icao_code = 'FRA'),
    false,
    190, 0,
    NOW() + INTERVAL '5 hours',
    NOW() + INTERVAL '17 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-EU-002');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-EU-003',
    (SELECT id FROM airport WHERE icao_code = 'CDG'),
    (SELECT id FROM airport WHERE icao_code = 'FCO'),
    false,
    210, 0,
    NOW() + INTERVAL '7 hours',
    NOW() + INTERVAL '19 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-EU-003');

-- ── Intra-Asia ────────────────────────────────────────────────────────────────

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-AS-001',
    (SELECT id FROM airport WHERE icao_code = 'DXB'),
    (SELECT id FROM airport WHERE icao_code = 'BOM'),
    false,
    170, 0,
    NOW() + INTERVAL '2 hours',
    NOW() + INTERVAL '14 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-AS-001');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-AS-002',
    (SELECT id FROM airport WHERE icao_code = 'NRT'),
    (SELECT id FROM airport WHERE icao_code = 'SIN'),
    false,
    240, 0,
    NOW() + INTERVAL '4 hours',
    NOW() + INTERVAL '16 hours',
    'SCHEDULED', 0.5
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-AS-002');

-- ── Inter-continentales ───────────────────────────────────────────────────────

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-IC-001',
    (SELECT id FROM airport WHERE icao_code = 'JFK'),
    (SELECT id FROM airport WHERE icao_code = 'LHR'),
    true,
    350, 0,
    NOW() + INTERVAL '6 hours',
    NOW() + INTERVAL '30 hours',
    'SCHEDULED', 1.0
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-IC-001');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-IC-002',
    (SELECT id FROM airport WHERE icao_code = 'GRU'),
    (SELECT id FROM airport WHERE icao_code = 'LHR'),
    true,
    380, 0,
    NOW() + INTERVAL '8 hours',
    NOW() + INTERVAL '32 hours',
    'SCHEDULED', 1.0
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-IC-002');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-IC-003',
    (SELECT id FROM airport WHERE icao_code = 'NRT'),
    (SELECT id FROM airport WHERE icao_code = 'LAX'),
    true,
    300, 0,
    NOW() + INTERVAL '10 hours',
    NOW() + INTERVAL '34 hours',
    'SCHEDULED', 1.0
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-IC-003');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-IC-004',
    (SELECT id FROM airport WHERE icao_code = 'DXB'),
    (SELECT id FROM airport WHERE icao_code = 'FRA'),
    true,
    320, 0,
    NOW() + INTERVAL '12 hours',
    NOW() + INTERVAL '36 hours',
    'SCHEDULED', 1.0
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-IC-004');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-IC-005',
    (SELECT id FROM airport WHERE icao_code = 'MAD'),
    (SELECT id FROM airport WHERE icao_code = 'BOG'),
    true,
    360, 0,
    NOW() + INTERVAL '14 hours',
    NOW() + INTERVAL '38 hours',
    'SCHEDULED', 1.0
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-IC-005');

INSERT INTO flight (
    flight_code,
    origin_airport_id, destination_airport_id,
    is_inter_continental,
    max_capacity, current_load,
    scheduled_departure, scheduled_arrival,
    status, transit_time_days
)
SELECT
    'FL-IC-006',
    (SELECT id FROM airport WHERE icao_code = 'PEK'),
    (SELECT id FROM airport WHERE icao_code = 'CDG'),
    true,
    400, 0,
    NOW() + INTERVAL '16 hours',
    NOW() + INTERVAL '40 hours',
    'SCHEDULED', 1.0
WHERE NOT EXISTS (SELECT 1 FROM flight WHERE flight_code = 'FL-IC-006');


-- ── Configuración de simulación inicial (singleton) ───────────────────────────
INSERT INTO simulation_config (
    scenario, simulation_days, execution_minutes,
    normal_threshold_pct, warning_threshold_pct,
    primary_algorithm, secondary_algorithm,
    is_running
)
SELECT
    'DAY_TO_DAY', 5, 60,
    70, 90,
    'GENETIC', 'ANT_COLONY',
    false
WHERE NOT EXISTS (SELECT 1 FROM simulation_config);
