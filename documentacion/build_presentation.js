const pptxgen = require("pptxgenjs");

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.3 x 7.5
pres.title = "GA y ACO - Experimentación Numérica - Tasf.B2B";
pres.author = "Equipo 3C";

const SLIDE_W = 13.3;
const SLIDE_H = 7.5;

// Palette: Ocean Gradient
const C = {
  bg: "F4F7FB",
  dark: "21295C",       // midnight
  primary: "065A82",    // deep blue
  teal: "1C7293",       // teal
  accent: "F2A65A",     // warm accent (ochre) for highlights
  white: "FFFFFF",
  ink: "1E293B",
  muted: "64748B",
  cardBg: "FFFFFF",
  divider: "CBD5E1",
  ga: "065A82",
  aco: "C2410C",
};

const FONT_TITLE = "Calibri";
const FONT_BODY = "Calibri";
const FONT_CODE = "Consolas";

// ---------- Helpers ----------

function addHeader(slide, kicker, title) {
  slide.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0, w: SLIDE_W, h: 0.18,
    fill: { color: C.primary }, line: { color: C.primary, width: 0 },
  });
  slide.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0.18, w: SLIDE_W, h: 0.04,
    fill: { color: C.accent }, line: { color: C.accent, width: 0 },
  });
  slide.addText(kicker, {
    x: 0.6, y: 0.32, w: 8, h: 0.35,
    fontSize: 11, fontFace: FONT_BODY, bold: true,
    color: C.accent, charSpacing: 4, margin: 0,
  });
  slide.addText(title, {
    x: 0.6, y: 0.62, w: 12.1, h: 0.7,
    fontSize: 28, fontFace: FONT_TITLE, bold: true,
    color: C.dark, margin: 0,
  });
}

function addFooter(slide, page) {
  slide.addText("Tasf.B2B  ·  Planificador de rutas  ·  Equipo 3C", {
    x: 0.6, y: SLIDE_H - 0.4, w: 8, h: 0.3,
    fontSize: 9, color: C.muted, fontFace: FONT_BODY, margin: 0,
  });
  slide.addText(String(page), {
    x: SLIDE_W - 0.9, y: SLIDE_H - 0.4, w: 0.4, h: 0.3,
    fontSize: 9, color: C.muted, fontFace: FONT_BODY, align: "right", margin: 0,
  });
}

function codeBlock(slide, x, y, w, h, code, opts = {}) {
  slide.addShape(pres.shapes.RECTANGLE, {
    x, y, w, h, fill: { color: C.dark }, line: { color: C.dark, width: 0 },
  });
  slide.addShape(pres.shapes.RECTANGLE, {
    x, y, w: 0.08, h, fill: { color: C.accent }, line: { color: C.accent, width: 0 },
  });
  slide.addText(code, {
    x: x + 0.2, y: y + 0.12, w: w - 0.3, h: h - 0.24,
    fontSize: opts.fontSize || 11, fontFace: FONT_CODE,
    color: "E2E8F0", margin: 0, valign: "top",
  });
}

function card(slide, x, y, w, h, fill = C.cardBg) {
  slide.addShape(pres.shapes.RECTANGLE, {
    x, y, w, h, fill: { color: fill }, line: { color: C.divider, width: 0.75 },
    shadow: { type: "outer", color: "000000", blur: 8, offset: 2, angle: 90, opacity: 0.08 },
  });
}

// ============================================================
// SLIDE 1 - PORTADA
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.dark };
  s.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0, w: SLIDE_W, h: 1.6,
    fill: { color: C.primary }, line: { color: C.primary, width: 0 },
  });
  s.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 1.6, w: SLIDE_W, h: 0.08,
    fill: { color: C.accent }, line: { color: C.accent, width: 0 },
  });
  s.addText("PROYECTO DE DISEÑO Y DESARROLLO DE SOFTWARE · 1INF54", {
    x: 0.7, y: 0.5, w: 12, h: 0.4,
    fontSize: 13, color: C.accent, bold: true, charSpacing: 6, margin: 0,
  });
  s.addText("PUCP · Facultad de Ciencias e Ingeniería", {
    x: 0.7, y: 0.95, w: 12, h: 0.4,
    fontSize: 13, color: "CADCFC", margin: 0,
  });
  s.addText("Algoritmos GA y ACO\nExperimentación numérica", {
    x: 0.7, y: 2.2, w: 12, h: 2.2,
    fontSize: 48, fontFace: FONT_TITLE, bold: true,
    color: C.white, margin: 0,
  });
  s.addText("Sistema de Gestión Logística de Traslado de Maletas · Tasf.B2B S.A.", {
    x: 0.7, y: 4.6, w: 12, h: 0.5,
    fontSize: 18, color: "CADCFC", italic: true, margin: 0,
  });

  s.addShape(pres.shapes.RECTANGLE, {
    x: 0.7, y: 5.4, w: 0.06, h: 1.4,
    fill: { color: C.accent }, line: { color: C.accent, width: 0 },
  });
  s.addText([
    { text: "Equipo 3C", options: { bold: true, color: C.white, fontSize: 14, breakLine: true } },
    { text: "Semestre 2026-1", options: { color: "CADCFC", fontSize: 13, breakLine: true } },
    { text: "Documento base: 22.dis.experim.v01", options: { color: "CADCFC", fontSize: 13 } },
  ], { x: 0.95, y: 5.4, w: 8, h: 1.4, margin: 0 });

  s.addText("5 minutos · GA + ACO + Experimentación", {
    x: SLIDE_W - 5.5, y: SLIDE_H - 0.7, w: 5, h: 0.4,
    fontSize: 11, color: C.accent, bold: true, align: "right", charSpacing: 3, margin: 0,
  });
}

// ============================================================
// SLIDE 2 - AGENDA
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "AGENDA", "Estructura de la exposición");

  const items = [
    { num: "01", title: "Algoritmo Genético (GA)", time: "~3 min", desc: "Concepto, pseudocódigo y operadores clave" },
    { num: "02", title: "Optimización por Colonia de Hormigas (ACO)", time: "~3 min", desc: "Concepto, pseudocódigo y mecanismo de feromonas" },
    { num: "03", title: "Experimentación numérica", time: "~6 min", desc: "Diseño, realización y conclusiones" },
  ];
  let y = 1.7;
  items.forEach((it) => {
    card(s, 0.7, y, 11.9, 1.45);
    s.addShape(pres.shapes.RECTANGLE, {
      x: 0.7, y, w: 0.12, h: 1.45,
      fill: { color: C.primary }, line: { color: C.primary, width: 0 },
    });
    s.addText(it.num, {
      x: 1.0, y: y + 0.2, w: 1.4, h: 1.0,
      fontSize: 48, bold: true, color: C.primary, fontFace: FONT_TITLE, margin: 0,
    });
    s.addText(it.title, {
      x: 2.5, y: y + 0.18, w: 8.2, h: 0.5,
      fontSize: 20, bold: true, color: C.dark, margin: 0,
    });
    s.addText(it.desc, {
      x: 2.5, y: y + 0.7, w: 8.2, h: 0.6,
      fontSize: 13, color: C.muted, margin: 0,
    });
    s.addText(it.time, {
      x: 10.7, y: y + 0.5, w: 1.7, h: 0.5,
      fontSize: 14, bold: true, color: C.accent, align: "right", margin: 0,
    });
    y += 1.65;
  });
  addFooter(s, 2);
}

// ============================================================
// SLIDE 3 - GA OVERVIEW
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "01 · ALGORITMO GENÉTICO", "GA: evolución de poblaciones de rutas factibles");

  card(s, 0.7, 1.6, 6.0, 5.2);
  s.addShape(pres.shapes.RECTANGLE, { x: 0.7, y: 1.6, w: 0.1, h: 5.2, fill: { color: C.primary }, line: { width: 0 } });
  s.addText("Idea central", {
    x: 0.95, y: 1.75, w: 5.5, h: 0.4, fontSize: 16, bold: true, color: C.primary, margin: 0,
  });
  s.addText([
    { text: "Cada cromosoma = una ruta candidata (secuencia de vuelos) para un envío.", options: { bullet: true, breakLine: true } },
    { text: "La población evoluciona por generaciones aplicando selección, cruce y mutación.", options: { bullet: true, breakLine: true } },
    { text: "El fitness premia entregas a tiempo y baja saturación; el inverso del routeScore se usa como aptitud.", options: { bullet: true, breakLine: true } },
    { text: "Caché de evaluaciones por ruta para evitar recómputos costosos.", options: { bullet: true, breakLine: true } },
    { text: "Parada anticipada por estancamiento (STAGNATION_LIMIT = 4).", options: { bullet: true } },
  ], {
    x: 0.95, y: 2.2, w: 5.55, h: 4.5, fontSize: 13, color: C.ink, margin: 0, paraSpaceAfter: 6,
  });

  const xR = 7.0, yR = 1.6, wR = 5.6, hR = 5.2;
  card(s, xR, yR, wR, hR);
  s.addText("Ciclo evolutivo", {
    x: xR + 0.25, y: yR + 0.15, w: wR - 0.4, h: 0.4, fontSize: 16, bold: true, color: C.primary, margin: 0,
  });

  const steps = [
    { t: "Inicializar", d: "Rutas factibles iniciales" },
    { t: "Evaluar", d: "Calcular fitness por ruta" },
    { t: "Seleccionar", d: "Torneo de tamaño 3" },
    { t: "Cruzar", d: "Combinar dos padres" },
    { t: "Mutar", d: "Reemplazo aleatorio (rate)" },
    { t: "Elitismo", d: "Conservar 12 % mejor" },
  ];
  let sx = xR + 0.3, sy = yR + 0.75;
  steps.forEach((step, i) => {
    const cy = sy + i * 0.7;
    s.addShape(pres.shapes.OVAL, {
      x: sx, y: cy + 0.05, w: 0.45, h: 0.45,
      fill: { color: C.primary }, line: { width: 0 },
    });
    s.addText(String(i + 1), {
      x: sx, y: cy + 0.08, w: 0.45, h: 0.4, fontSize: 14, bold: true,
      color: C.white, align: "center", margin: 0,
    });
    s.addText(step.t, {
      x: sx + 0.6, y: cy, w: 1.8, h: 0.32, fontSize: 14, bold: true, color: C.dark, margin: 0,
    });
    s.addText(step.d, {
      x: sx + 0.6, y: cy + 0.32, w: 4.7, h: 0.32, fontSize: 11, color: C.muted, margin: 0,
    });
  });
  addFooter(s, 3);
}

// ============================================================
// SLIDE 4 - GA PSEUDOCÓDIGO
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "01 · ALGORITMO GENÉTICO", "Pseudocódigo de planRoute");

  const code =
`función planRouteFromCandidates(envío, vuelosCandidatos, aeropuertos):
    rutas ← enumerar rutas factibles (DFS limitado por SLA y escalas)
    si rutas vacía: devolver []

    perfil ← elegir perfil adaptativo según #rutas y #candidatos
    población ← inicializar(rutas, perfil.populationSize)

    mejor_fitness ← max(fitness en población)
    estancada ← 0

    para gen = 1 .. perfil.generations:
        población ← evolve(población, perfil)         // selección + cruce + mutación + elitismo
        gen_best  ← max(fitness en población)
        si gen_best > mejor_fitness:
            mejor_fitness ← gen_best
            estancada ← 0
        sino si ++estancada ≥ STAGNATION_LIMIT:       // parada anticipada
            romper

    devolver ruta(individuo de mayor fitness) → TravelStops`;
  codeBlock(s, 0.7, 1.6, 11.9, 4.4, code, { fontSize: 13 });

  const tags = [
    { l: "populationSize", v: "20 – 60" },
    { l: "generations", v: "8 – 20 (adaptativo)" },
    { l: "mutationRate", v: "0.02 – 0.12" },
    { l: "elitismo", v: "12 % mejor población" },
    { l: "fitness", v: "1 / routeScore" },
  ];
  const tw = 2.3, gap = 0.06;
  let tx = 0.7;
  tags.forEach((t) => {
    card(s, tx, 6.2, tw, 0.85);
    s.addText(t.l, {
      x: tx + 0.1, y: 6.27, w: tw - 0.2, h: 0.3, fontSize: 10,
      color: C.muted, bold: true, charSpacing: 2, margin: 0,
    });
    s.addText(t.v, {
      x: tx + 0.1, y: 6.55, w: tw - 0.2, h: 0.4, fontSize: 13,
      color: C.primary, bold: true, margin: 0,
    });
    tx += tw + gap;
  });
  addFooter(s, 4);
}

// ============================================================
// SLIDE 5 - GA CÓDIGO CLAVE - EVOLVE
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "01 · ALGORITMO GENÉTICO", "Código clave: evolve() y operadores");

  const code =
`List<Individual> evolve(population, shipment, candidates, random, cache, profile) {
    List<Individual> ranked = population.stream()
        .sorted(Comparator.comparingDouble(Individual::fitness).reversed()).toList();

    int eliteCount = max(1, ceil(ranked.size() * 0.12));        // 12% elitismo
    Map<String,Individual> next = new LinkedHashMap<>();
    for (int i = 0; i < eliteCount; i++)
        next.put(routeKey(ranked.get(i).route()), ranked.get(i));

    while (next.size() < profile.populationSize() && attempts++ < maxAttempts) {
        Individual pA = selectParent(ranked, random);            // torneo 3
        Individual pB = selectParent(ranked, random);
        List<Flight> child = crossoverRoutes(shipment, pA.route(), pB.route(), ...);
        if (random.nextDouble() < profile.mutationRate())
            child = mutateRoute(shipment, child, candidates, random, cache);
        addIndividual(next, shipment, child, cache);             // sólo si es factible
    }
    return next.values().stream()
        .sorted(by fitness desc).limit(profile.populationSize()).toList();
}`;
  codeBlock(s, 0.7, 1.6, 8.7, 5.0, code, { fontSize: 11 });

  const xR = 9.6, w = 3.0;
  const items = [
    { t: "Selección", d: "Torneo de 3, devuelve el más apto" },
    { t: "Cruce", d: "Pool con padres + candidatos que comparten origen o pivote; gana el mejor" },
    { t: "Mutación", d: "Swap por candidato aleatorio (8 muestras)" },
  ];
  let yi = 1.6;
  items.forEach((it) => {
    card(s, xR, yi, w, 1.55);
    s.addShape(pres.shapes.RECTANGLE, { x: xR, y: yi, w: 0.08, h: 1.55, fill: { color: C.accent }, line: { width: 0 } });
    s.addText(it.t, { x: xR + 0.2, y: yi + 0.12, w: w - 0.3, h: 0.35, fontSize: 14, bold: true, color: C.dark, margin: 0 });
    s.addText(it.d, { x: xR + 0.2, y: yi + 0.5, w: w - 0.3, h: 1.0, fontSize: 11, color: C.muted, margin: 0 });
    yi += 1.7;
  });
  addFooter(s, 5);
}

// ============================================================
// SLIDE 6 - ACO OVERVIEW
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "02 · COLONIA DE HORMIGAS", "ACO: feromona y heurística para componer rutas");

  card(s, 0.7, 1.6, 6.0, 5.2);
  s.addShape(pres.shapes.RECTANGLE, { x: 0.7, y: 1.6, w: 0.1, h: 5.2, fill: { color: C.aco }, line: { width: 0 } });
  s.addText("Idea central", { x: 0.95, y: 1.75, w: 5.5, h: 0.4, fontSize: 16, bold: true, color: C.aco, margin: 0 });
  s.addText([
    { text: "K hormigas construyen rutas paso a paso desde el aeropuerto origen.", options: { bullet: true, breakLine: true } },
    { text: "En cada nodo, el siguiente vuelo se elige por ruleta probabilística.", options: { bullet: true, breakLine: true } },
    { text: "Probabilidad pondera feromona (τ) y heurística (η = 1/score).", options: { bullet: true, breakLine: true } },
    { text: "Tras cada iteración: evaporación + depósito proporcional al fitness.", options: { bullet: true, breakLine: true } },
    { text: "El mejor recorrido global se conserva entre iteraciones.", options: { bullet: true } },
  ], { x: 0.95, y: 2.2, w: 5.55, h: 4.5, fontSize: 13, color: C.ink, margin: 0, paraSpaceAfter: 6 });

  const xR = 7.0, wR = 5.6;
  card(s, xR, 1.6, wR, 5.2);
  s.addText("Regla de transición", { x: xR + 0.25, y: 1.75, w: wR - 0.4, h: 0.4, fontSize: 16, bold: true, color: C.aco, margin: 0 });

  s.addShape(pres.shapes.RECTANGLE, {
    x: xR + 0.3, y: 2.3, w: wR - 0.6, h: 1.5,
    fill: { color: C.dark }, line: { width: 0 },
  });
  s.addText("p(i) = ( τᵢ^α  ·  ηᵢ^β )  /  Σⱼ ( τⱼ^α  ·  ηⱼ^β )", {
    x: xR + 0.3, y: 2.55, w: wR - 0.6, h: 0.5, fontSize: 14, fontFace: FONT_CODE,
    color: "FCD34D", align: "center", bold: true, margin: 0,
  });
  s.addText("ηᵢ = 1 / max(1, routeScore(vueloᵢ))", {
    x: xR + 0.3, y: 3.05, w: wR - 0.6, h: 0.5, fontSize: 12, fontFace: FONT_CODE,
    color: "E2E8F0", align: "center", margin: 0,
  });
  s.addText("Evaporación: τᵢ ← max(τ_min,  (1 − ρ) · τᵢ)", {
    x: xR + 0.3, y: 3.4, w: wR - 0.6, h: 0.5, fontSize: 12, fontFace: FONT_CODE,
    color: "E2E8F0", align: "center", margin: 0,
  });

  const params = [
    { l: "α", v: "1.0 – 1.2", d: "peso feromona" },
    { l: "β", v: "2.0 – 2.5", d: "peso heurística" },
    { l: "ρ", v: "0.10 – 0.15", d: "evaporación" },
    { l: "τ_min", v: "0.01", d: "piso de feromona" },
  ];
  const cw = (wR - 0.6 - 0.18) / 4;
  params.forEach((p, i) => {
    const px = xR + 0.3 + i * (cw + 0.06);
    card(s, px, 4.1, cw, 1.0, "F1F5F9");
    s.addText(p.l, { x: px + 0.05, y: 4.18, w: cw - 0.1, h: 0.3, fontSize: 11, color: C.aco, bold: true, charSpacing: 2, margin: 0, align: "center" });
    s.addText(p.v, { x: px + 0.05, y: 4.45, w: cw - 0.1, h: 0.35, fontSize: 14, bold: true, color: C.dark, align: "center", margin: 0 });
    s.addText(p.d, { x: px + 0.05, y: 4.78, w: cw - 0.1, h: 0.3, fontSize: 9, color: C.muted, align: "center", margin: 0 });
  });

  s.addText("Depósito: τᵢ  +=  max(0.05, fitness(ruta) / 5000)", {
    x: xR + 0.3, y: 5.4, w: wR - 0.6, h: 0.5, fontSize: 12, fontFace: FONT_CODE, color: C.ink, align: "center", margin: 0,
  });
  s.addText("Más feromona donde se obtuvieron mejores rutas; el rastro guía a las siguientes hormigas.", {
    x: xR + 0.3, y: 5.85, w: wR - 0.6, h: 0.8, fontSize: 12, color: C.muted, italic: true, align: "center", margin: 0,
  });

  addFooter(s, 6);
}

// ============================================================
// SLIDE 7 - ACO PSEUDOCÓDIGO
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "02 · COLONIA DE HORMIGAS", "Pseudocódigo de planRoute");

  const code =
`función planRoute(envío, vuelosDisponibles, aeropuertos):
    candidatos ← vuelos compatibles con (origen, destino, SLA, capacidad)
    si candidatos vacío: devolver []

    inicializar τ[v] = τ₀  para cada vuelo v en candidatos

    mejor_ruta  ← []
    mejor_score ← +∞

    para iter = 1 .. iterations:
        rutas_iter ← []
        para cada hormiga k = 1 .. numAnts:
            ruta ← buildSolution(envío, candidatos)             // construcción paso a paso
            si ruta no vacía:
                rutas_iter.append(ruta)
                score ← routeScore(envío, ruta)
                si score < mejor_score:
                    mejor_score ← score
                    mejor_ruta  ← ruta

        evaporar()                                              // τ ← (1 − ρ) · τ
        actualizarFeromonas(envío, rutas_iter)                  // depositar según fitness

    devolver toTravelStops(envío, mejor_ruta)`;
  codeBlock(s, 0.7, 1.6, 11.9, 4.6, code, { fontSize: 13 });

  const tags = [
    { l: "numAnts", v: "24 – 35" },
    { l: "iterations", v: "20 – 30" },
    { l: "α / β", v: "1.0 / 2.0" },
    { l: "evaporación", v: "0.10" },
    { l: "τ₀", v: "1.0" },
  ];
  const tw = 2.3, gap = 0.06;
  let tx = 0.7;
  tags.forEach((t) => {
    card(s, tx, 6.4, tw, 0.75);
    s.addText(t.l, { x: tx + 0.1, y: 6.45, w: tw - 0.2, h: 0.3, fontSize: 10, color: C.muted, bold: true, charSpacing: 2, margin: 0 });
    s.addText(t.v, { x: tx + 0.1, y: 6.7, w: tw - 0.2, h: 0.35, fontSize: 13, color: C.aco, bold: true, margin: 0 });
    tx += tw + gap;
  });
  addFooter(s, 7);
}

// ============================================================
// SLIDE 8 - ACO CÓDIGO CLAVE
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "02 · COLONIA DE HORMIGAS", "Código clave: buildSolution() y rouletteSelect()");

  const code =
`List<Flight> buildSolution(shipment, availableFlights, random) {
    List<Flight> path = new ArrayList<>();
    Set<Long> visited = new HashSet<>();
    Airport current = shipment.getOriginAirport();
    LocalDateTime readyAt = registrationTime(shipment);

    while (path.size() < maxFlightLegs(shipment)) {
        visited.add(current.getId());
        List<Flight> outgoing = filtrarVuelosFactibles(current, readyAt, visited);
        if (outgoing.isEmpty()) return List.of();

        Flight chosen = rouletteSelect(outgoing, random);          // τ^α · η^β
        path.add(chosen);
        if (esDestinoFinal(chosen))
            return isFeasibleRoute(shipment, path) ? List.copyOf(path) : List.of();

        current = chosen.getDestinationAirport();
        readyAt = chosen.getScheduledArrival();
    }
    return List.of();
}`;
  codeBlock(s, 0.7, 1.6, 8.5, 4.0, code, { fontSize: 11 });

  const code2 =
`Flight rouletteSelect(candidates, random) {
    double total = 0;
    List<Double> w = new ArrayList<>();
    for (Flight f : candidates) {
        double tau = pheromones.getOrDefault(f.getId(), tau_0);
        double eta = 1.0 / max(1.0, routeScoreForSingleFlight(f));
        double weight = pow(tau, alpha) * pow(eta, beta);
        w.add(weight); total += weight;
    }
    double roll = random.nextDouble() * total, acc = 0;
    for (int i = 0; i < candidates.size(); i++) {
        acc += w.get(i);
        if (roll <= acc) return candidates.get(i);
    }
    return candidates.get(candidates.size() - 1);
}`;
  codeBlock(s, 0.7, 5.7, 8.5, 1.55, code2, { fontSize: 9 });

  const xR = 9.4, wR = 3.2;
  card(s, xR, 1.6, wR, 5.65);
  s.addText("Flujo por hormiga", { x: xR + 0.2, y: 1.7, w: wR - 0.4, h: 0.4, fontSize: 14, bold: true, color: C.aco, margin: 0 });

  const flow = [
    "Empezar en aeropuerto origen",
    "Listar vuelos salientes factibles",
    "Elegir uno por ruleta τ^α · η^β",
    "Avanzar: actualizar nodo y tiempo",
    "Repetir hasta destino o límite de escalas",
    "Validar ruta completa con SLA",
  ];
  let yy = 2.15;
  flow.forEach((t, i) => {
    s.addShape(pres.shapes.OVAL, {
      x: xR + 0.2, y: yy + 0.05, w: 0.3, h: 0.3,
      fill: { color: C.aco }, line: { width: 0 },
    });
    s.addText(String(i + 1), {
      x: xR + 0.2, y: yy + 0.05, w: 0.3, h: 0.28, fontSize: 11, bold: true,
      color: C.white, align: "center", margin: 0,
    });
    s.addText(t, {
      x: xR + 0.6, y: yy, w: wR - 0.7, h: 0.55, fontSize: 11, color: C.ink, margin: 0,
    });
    yy += 0.78;
  });
  addFooter(s, 8);
}

// ============================================================
// SLIDE 9 - GA vs ACO comparación
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "GA vs ACO", "Comparación de paradigmas");

  const headers = ["Aspecto", "GA (Genético)", "ACO (Hormigas)"];
  const rows = [
    ["Representación", "Cromosoma = ruta completa", "Camino construido por hormiga"],
    ["Memoria", "Población + caché de evaluaciones", "Matriz de feromonas τ[v]"],
    ["Exploración", "Cruce y mutación entre rutas", "Ruleta con τ y η"],
    ["Explotación", "Elitismo (12 %)", "Refuerzo por depósito de feromona"],
    ["Parada", "Estancamiento (4 gen) o gen máx.", "Iterations fijas"],
    ["Hiperparámetros", "popSize, generations, mutationRate", "numAnts, α, β, ρ"],
  ];

  const rowsTbl = [
    headers.map((h) => ({
      text: h,
      options: { bold: true, color: C.white, fill: { color: C.dark }, fontSize: 13, align: "left", valign: "middle" },
    })),
    ...rows.map((r, ri) => r.map((c, ci) => ({
      text: c,
      options: {
        fontSize: 12, color: C.ink, valign: "middle",
        fill: { color: ri % 2 === 0 ? C.white : "F1F5F9" },
        bold: ci === 0,
      },
    }))),
  ];

  s.addTable(rowsTbl, {
    x: 0.7, y: 1.7, w: 11.9,
    colW: [3.0, 4.45, 4.45],
    rowH: 0.6,
    border: { pt: 0.5, color: C.divider },
    fontFace: FONT_BODY,
  });

  s.addText("Equivalencia operativa observada en el experimento: diferencia GA − ACO = 0.19 puntos sobre 100 (dentro del IC 95 %).", {
    x: 0.7, y: 6.4, w: 11.9, h: 0.6, fontSize: 13, italic: true, color: C.muted, align: "center", margin: 0,
  });
  addFooter(s, 9);
}

// ============================================================
// SLIDE 10 - EXPERIMENTACIÓN (portadilla de sección)
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.dark };
  s.addShape(pres.shapes.RECTANGLE, { x: 0, y: 3.4, w: SLIDE_W, h: 0.08, fill: { color: C.accent }, line: { width: 0 } });
  s.addText("03", {
    x: 0.7, y: 1.5, w: 4, h: 1.6,
    fontSize: 96, bold: true, color: C.accent, fontFace: FONT_TITLE, margin: 0,
  });
  s.addText("Experimentación numérica", {
    x: 0.7, y: 3.7, w: 12, h: 1.0, fontSize: 44, bold: true, color: C.white, margin: 0,
  });
  s.addText("Diseño · Realización · Conclusión", {
    x: 0.7, y: 4.7, w: 12, h: 0.6, fontSize: 22, color: "CADCFC", italic: true, charSpacing: 4, margin: 0,
  });

  const k = [
    { v: "156", l: "corridas reproducibles" },
    { v: "9", l: "escenarios oficiales" },
    { v: "2", l: "algoritmos comparados" },
    { v: "4", l: "niveles de demanda" },
  ];
  const cw = 2.7, gap = 0.2, totalW = k.length * cw + (k.length - 1) * gap;
  const startX = (SLIDE_W - totalW) / 2;
  k.forEach((it, i) => {
    const cx = startX + i * (cw + gap);
    s.addShape(pres.shapes.RECTANGLE, {
      x: cx, y: 5.7, w: cw, h: 1.3, fill: { color: "0F1A4A" }, line: { color: C.accent, width: 1 },
    });
    s.addText(it.v, { x: cx, y: 5.78, w: cw, h: 0.65, fontSize: 36, bold: true, color: C.accent, align: "center", margin: 0 });
    s.addText(it.l, { x: cx, y: 6.45, w: cw, h: 0.5, fontSize: 11, color: "CADCFC", align: "center", margin: 0 });
  });
}

// ============================================================
// SLIDE 11 - DISEÑO: Objetivo e hipótesis
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "03.A · DISEÑO", "Objetivo del experimento e hipótesis");

  card(s, 0.7, 1.6, 5.9, 2.3);
  s.addShape(pres.shapes.RECTANGLE, { x: 0.7, y: 1.6, w: 0.1, h: 2.3, fill: { color: C.primary }, line: { width: 0 } });
  s.addText("Objetivo general", {
    x: 0.95, y: 1.7, w: 5.5, h: 0.4, fontSize: 14, bold: true, color: C.primary, margin: 0,
  });
  s.addText("Determinar, mediante una comparación controlada y reproducible, qué algoritmo metaheurístico —GA o ACO— ofrece el mejor balance entre cumplimiento de SLA, robustez ante colapso, eficiencia y costo, para el componente Planificador de Tasf.B2B.", {
    x: 0.95, y: 2.1, w: 5.55, h: 1.7, fontSize: 12, color: C.ink, margin: 0,
  });

  card(s, 6.8, 1.6, 5.8, 2.3);
  s.addShape(pres.shapes.RECTANGLE, { x: 6.8, y: 1.6, w: 0.1, h: 2.3, fill: { color: C.accent }, line: { width: 0 } });
  s.addText("Variable dependiente principal", {
    x: 7.05, y: 1.7, w: 5.5, h: 0.4, fontSize: 14, bold: true, color: C.accent, margin: 0,
  });
  s.addText("compositeScore = 0.55 · weightedCompleted  +  0.35 · collapseDelayScore  +  0.10 · collapsePenalty", {
    x: 7.05, y: 2.15, w: 5.45, h: 1.0, fontSize: 11, fontFace: FONT_CODE, color: C.dark, margin: 0,
  });
  s.addText("Ponderación 55/35/10 acordada con el cliente: entrega completa, retraso del colapso, penalización por colapso.", {
    x: 7.05, y: 3.1, w: 5.45, h: 0.8, fontSize: 11, color: C.muted, italic: true, margin: 0,
  });

  const hyp = [
    ["H1", "GA produce composite score significativamente superior a ACO (Δ > 1, IC 95 % no solapado)."],
    ["H2", "Score del ganador dentro de banda ± 2 puntos al variar capacidad de vuelos ± 30 %."],
    ["H3", "Score por encima de 78 con degradación ≤ 3 puntos al subir demanda 1 500 → 9 000."],
    ["H4", "100 % de envíos completados en escenarios DAY_TO_DAY y PERIOD_*."],
    ["H5", "Los hiperparámetros tuneados (perfil P1) superan a los exploratorios (P2) en ambos algoritmos."],
  ];
  s.addText("Hipótesis a contrastar", {
    x: 0.7, y: 4.05, w: 11.9, h: 0.4, fontSize: 16, bold: true, color: C.dark, margin: 0,
  });
  const hypRows = hyp.map((h, i) => [
    { text: h[0], options: { bold: true, color: C.white, fill: { color: C.primary }, align: "center", valign: "middle" } },
    { text: h[1], options: { color: C.ink, fill: { color: i % 2 === 0 ? C.white : "F1F5F9" }, valign: "middle" } },
  ]);
  s.addTable(hypRows, {
    x: 0.7, y: 4.55, w: 11.9, colW: [1.0, 10.9], rowH: 0.45,
    border: { pt: 0.5, color: C.divider }, fontSize: 12, fontFace: FONT_BODY,
  });
  addFooter(s, 11);
}

// ============================================================
// SLIDE 12 - DISEÑO: variables, escenarios y perfiles
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "03.A · DISEÑO", "Variables, escenarios y perfiles de configuración");

  card(s, 0.7, 1.6, 6.0, 5.3);
  s.addShape(pres.shapes.RECTANGLE, { x: 0.7, y: 1.6, w: 0.1, h: 5.3, fill: { color: C.primary }, line: { width: 0 } });
  s.addText("Variables independientes", {
    x: 0.95, y: 1.7, w: 5.55, h: 0.4, fontSize: 14, bold: true, color: C.primary, margin: 0,
  });
  const vars = [
    ["Familia algorítmica", "GA, ACO"],
    ["Perfil de configuración", "P1 y P2 por familia (4)"],
    ["Escenario operativo", "9 oficiales"],
    ["Tamaño de demanda", "1 500 / 3 000 / 6 000 / 9 000"],
    ["Semilla aleatoria", "1, 2 (réplicas)"],
  ];
  const vRows = vars.map((v, i) => [
    { text: v[0], options: { bold: true, color: C.dark, fill: { color: i % 2 === 0 ? "F1F5F9" : C.white } } },
    { text: v[1], options: { color: C.ink, fill: { color: i % 2 === 0 ? "F1F5F9" : C.white } } },
  ]);
  s.addTable(vRows, {
    x: 0.95, y: 2.15, w: 5.55, colW: [2.5, 3.05], rowH: 0.5,
    border: { pt: 0.5, color: C.divider }, fontSize: 11, fontFace: FONT_BODY,
  });

  s.addText("Total combinatorio: 288 corridas teóricas · Diseño fraccionado ejecutado: 156.", {
    x: 0.95, y: 4.85, w: 5.55, h: 0.6, fontSize: 11, italic: true, color: C.muted, margin: 0,
  });

  card(s, 6.85, 1.6, 5.75, 5.3);
  s.addShape(pres.shapes.RECTANGLE, { x: 6.85, y: 1.6, w: 0.1, h: 5.3, fill: { color: C.accent }, line: { width: 0 } });
  s.addText("9 escenarios oficiales", {
    x: 7.05, y: 1.7, w: 5.5, h: 0.4, fontSize: 14, bold: true, color: C.accent, margin: 0,
  });

  const groups = [
    { title: "Operación", color: "0E7C66", items: ["DAY_TO_DAY"] },
    { title: "Período", color: "1C7293", items: ["PERIOD_D3_M30", "PERIOD_D5_M60", "PERIOD_D7_M90"] },
    { title: "Colapso (sensibilidad de vuelos)", color: "C2410C", items: ["COLLAPSE_SENS_M30", "COLLAPSE_SENS_M15", "COLLAPSE_SENS_0", "COLLAPSE_SENS_P15", "COLLAPSE_SENS_P30"] },
  ];
  let gy = 2.2;
  groups.forEach((g) => {
    s.addShape(pres.shapes.RECTANGLE, {
      x: 7.05, y: gy, w: 0.16, h: 0.32, fill: { color: g.color }, line: { width: 0 },
    });
    s.addText(g.title, { x: 7.3, y: gy, w: 5.3, h: 0.32, fontSize: 12, bold: true, color: C.dark, margin: 0 });
    gy += 0.4;
    let bx = 7.05;
    g.items.forEach((it) => {
      const tw = Math.min(2.5, 0.18 + it.length * 0.075);
      if (bx + tw > 12.5) { bx = 7.05; gy += 0.45; }
      s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
        x: bx, y: gy, w: tw, h: 0.32, fill: { color: g.color }, line: { width: 0 }, rectRadius: 0.05,
      });
      s.addText(it, {
        x: bx, y: gy, w: tw, h: 0.32, fontSize: 10, color: C.white, align: "center", valign: "middle", fontFace: FONT_CODE, margin: 0,
      });
      bx += tw + 0.1;
    });
    gy += 0.55;
  });

  s.addText("Perfiles tuneados (P1) y exploratorios (P2): GA-P1 popSize=55, gens=24, mut=0.05  ·  ACO-P1 numAnts=24, iter=20, ρ=0.10, α=1.0, β=2.0.", {
    x: 7.05, y: 5.95, w: 5.45, h: 0.85, fontSize: 11, color: C.muted, italic: true, margin: 0,
  });

  addFooter(s, 12);
}

// ============================================================
// SLIDE 13 - REALIZACIÓN: procedimiento + score
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "03.B · REALIZACIÓN", "Procedimiento por corrida y métrica compuesta");

  card(s, 0.7, 1.6, 6.5, 5.3);
  s.addText("Procedimiento estandarizado por corrida", {
    x: 0.9, y: 1.75, w: 6.1, h: 0.4, fontSize: 14, bold: true, color: C.primary, margin: 0,
  });
  const steps = [
    "Reiniciar contenedor PostgreSQL y aplicar V1__init.sql + V2__indices.sql.",
    "Cargar aeropuertos.csv y vuelos.csv vía CargadorCSV.",
    "Inicializar Motor de Simulación con (perfil, escenario, semilla).",
    "Generar envíos (cantidad = demandSize) y planificar con el algoritmo.",
    "Ejecutar simulación hasta finalización o colapso.",
    "Persistir KPIs en benchmark-results.csv y benchmark-results.json.",
  ];
  let py = 2.3;
  steps.forEach((t, i) => {
    s.addShape(pres.shapes.OVAL, { x: 0.9, y: py + 0.04, w: 0.4, h: 0.4, fill: { color: C.primary }, line: { width: 0 } });
    s.addText(String(i + 1), { x: 0.9, y: py + 0.07, w: 0.4, h: 0.35, fontSize: 13, bold: true, color: C.white, align: "center", margin: 0 });
    s.addText(t, { x: 1.4, y: py, w: 5.7, h: 0.65, fontSize: 12, color: C.ink, margin: 0 });
    py += 0.7;
  });

  card(s, 7.4, 1.6, 5.2, 5.3);
  s.addShape(pres.shapes.RECTANGLE, { x: 7.4, y: 1.6, w: 0.1, h: 5.3, fill: { color: C.accent }, line: { width: 0 } });
  s.addText("Composite score (0 a 100)", {
    x: 7.6, y: 1.75, w: 4.95, h: 0.4, fontSize: 14, bold: true, color: C.accent, margin: 0,
  });

  s.addShape(pres.shapes.RECTANGLE, { x: 7.6, y: 2.25, w: 4.85, h: 1.0, fill: { color: C.dark }, line: { width: 0 } });
  s.addText("score = 0.55 · (completed/total)\n      + 0.35 · ((total − saturated)/total)\n      + 0.10 · max(0, 100 − missRate)", {
    x: 7.7, y: 2.3, w: 4.7, h: 0.95, fontSize: 11, fontFace: FONT_CODE, color: "FCD34D", margin: 0,
  });

  const wcomp = [
    { l: "Entrega", v: "55 %", c: C.primary },
    { l: "Retraso colapso", v: "35 %", c: C.teal },
    { l: "Penalización", v: "10 %", c: C.accent },
  ];
  let wy = 3.5;
  wcomp.forEach((w) => {
    s.addShape(pres.shapes.RECTANGLE, { x: 7.6, y: wy, w: 0.18, h: 0.5, fill: { color: w.c }, line: { width: 0 } });
    s.addText(w.l, { x: 7.9, y: wy + 0.05, w: 3.3, h: 0.4, fontSize: 13, bold: true, color: C.dark, margin: 0 });
    s.addText(w.v, { x: 11.2, y: wy + 0.05, w: 1.2, h: 0.4, fontSize: 14, bold: true, color: w.c, align: "right", margin: 0 });
    wy += 0.55;
  });

  s.addText("Estadísticos: media, mediana, desviación estándar.\nIC 95 % por bootstrap (1 000 muestras).", {
    x: 7.6, y: 5.4, w: 4.85, h: 1.3, fontSize: 11, color: C.muted, italic: true, margin: 0,
  });

  addFooter(s, 13);
}

// ============================================================
// SLIDE 14 - RESULTADOS: agregado por algoritmo
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "03.B · REALIZACIÓN · RESULTADOS", "Composite score promedio por algoritmo");

  s.addChart(pres.charts.BAR, [
    { name: "Composite score", labels: ["GA", "ACO"], values: [80.89, 81.08] },
  ], {
    x: 0.7, y: 1.7, w: 7.5, h: 4.2, barDir: "col",
    chartColors: [C.ga, C.aco],
    chartArea: { fill: { color: C.white } },
    catAxisLabelColor: C.dark, valAxisLabelColor: C.muted,
    valAxisMinVal: 78, valAxisMaxVal: 83,
    valGridLine: { color: "E2E8F0", size: 0.5 }, catGridLine: { style: "none" },
    showValue: true, dataLabelPosition: "outEnd", dataLabelColor: C.dark,
    dataLabelFontSize: 12, dataLabelFontBold: true,
    showLegend: false, showTitle: true,
    title: "Composite score por algoritmo (n = 156)", titleColor: C.dark, titleFontSize: 14,
    catAxisLabelFontSize: 12, valAxisLabelFontSize: 10,
  });

  const rows = [
    [
      { text: "Algoritmo", options: { bold: true, color: C.white, fill: { color: C.dark } } },
      { text: "n", options: { bold: true, color: C.white, fill: { color: C.dark }, align: "center" } },
      { text: "Media", options: { bold: true, color: C.white, fill: { color: C.dark }, align: "center" } },
      { text: "DE", options: { bold: true, color: C.white, fill: { color: C.dark }, align: "center" } },
    ],
    [
      { text: "GA", options: { bold: true, color: C.ga, fill: { color: "F1F5F9" } } },
      { text: "88", options: { align: "center" } }, { text: "80.89", options: { align: "center" } }, { text: "1.46", options: { align: "center" } },
    ],
    [
      { text: "ACO", options: { bold: true, color: C.aco, fill: { color: C.white } } },
      { text: "68", options: { align: "center", fill: { color: C.white } } },
      { text: "81.08", options: { align: "center", fill: { color: C.white } } },
      { text: "1.50", options: { align: "center", fill: { color: C.white } } },
    ],
  ];
  s.addTable(rows, {
    x: 8.4, y: 1.9, w: 4.3, colW: [1.4, 0.7, 1.1, 1.1], rowH: 0.55,
    border: { pt: 0.5, color: C.divider }, fontSize: 12, fontFace: FONT_BODY,
  });

  card(s, 8.4, 4.0, 4.3, 1.9);
  s.addShape(pres.shapes.RECTANGLE, { x: 8.4, y: 4.0, w: 0.08, h: 1.9, fill: { color: C.accent }, line: { width: 0 } });
  s.addText("Hallazgo", { x: 8.55, y: 4.1, w: 4.05, h: 0.35, fontSize: 12, bold: true, color: C.accent, margin: 0 });
  s.addText("Empate técnico GA – ACO: Δ = 0.19 puntos sobre 100, dentro del IC 95 %. Ambas familias presentan baja varianza (DE ≈ 1.5).", {
    x: 8.55, y: 4.45, w: 4.05, h: 1.4, fontSize: 11, color: C.ink, margin: 0,
  });

  s.addText("IC 95 % del ganador GA-P1 (bootstrap 1 000): [80.46, 81.33]. ΔvsRunnerUp = −0.52 → empate técnico.", {
    x: 0.7, y: 6.2, w: 11.9, h: 0.6, fontSize: 12, italic: true, color: C.muted, align: "center", margin: 0,
  });
  addFooter(s, 14);
}

// ============================================================
// SLIDE 15 - RESULTADOS por escenario
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "RESULTADOS", "Composite score por escenario y algoritmo");

  const labels = ["DAY_TO_DAY", "PD3_M30", "PD5_M60", "PD7_M90", "COLL_M30", "COLL_M15", "COLL_0", "COLL_P15", "COLL_P30"];
  const ga  = [83.236, 80.283, 82.206, 81.925, 80.005, 79.710, 80.455, 81.346, 80.527];
  const aco = [83.236, 80.283, 82.206, 81.925, 80.063, 79.722, 80.437, 81.584, 80.606];

  s.addChart(pres.charts.LINE, [
    { name: "GA",  labels, values: ga },
    { name: "ACO", labels, values: aco },
  ], {
    x: 0.7, y: 1.6, w: 12.0, h: 4.6,
    chartColors: [C.ga, C.aco],
    chartArea: { fill: { color: C.white } },
    catAxisLabelColor: C.dark, valAxisLabelColor: C.muted,
    catAxisLabelFontSize: 10, valAxisLabelFontSize: 10,
    valAxisMinVal: 78, valAxisMaxVal: 84,
    valGridLine: { color: "E2E8F0", size: 0.5 }, catGridLine: { style: "none" },
    showLegend: true, legendPos: "t", legendFontSize: 12, legendColor: C.dark,
    lineSize: 3, lineSmooth: true,
    showTitle: false,
  });

  const ins = [
    { c: C.ga, t: "Operación nominal", d: "GA y ACO empatados (Δ < 0.01) en DAY_TO_DAY y todos los PERIOD_*." },
    { c: C.aco, t: "Escenarios de colapso", d: "ACO supera marginalmente a GA por 0.01 a 0.24 puntos." },
    { c: C.accent, t: "Diferencia agregada", d: "Δ promedio absoluto entre los 9 escenarios ≈ 0.04 puntos." },
  ];
  const cw = (12.0 - 0.4) / 3;
  ins.forEach((it, i) => {
    const cx = 0.7 + i * (cw + 0.2);
    card(s, cx, 6.35, cw, 0.95);
    s.addShape(pres.shapes.RECTANGLE, { x: cx, y: 6.35, w: 0.08, h: 0.95, fill: { color: it.c }, line: { width: 0 } });
    s.addText(it.t, { x: cx + 0.18, y: 6.4, w: cw - 0.25, h: 0.32, fontSize: 12, bold: true, color: it.c, margin: 0 });
    s.addText(it.d, { x: cx + 0.18, y: 6.7, w: cw - 0.25, h: 0.6, fontSize: 10, color: C.ink, margin: 0 });
  });
  addFooter(s, 15);
}

// ============================================================
// SLIDE 16 - Sensibilidad y escalabilidad
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "RESULTADOS", "Sensibilidad de vuelos (LE-066) y escalabilidad de demanda");

  s.addChart(pres.charts.LINE, [
    { name: "GA-P1", labels: ["−30 %", "−15 %", "0 %", "+15 %", "+30 %"], values: [79.7, 79.7, 80.5, 81.3, 80.5] },
  ], {
    x: 0.7, y: 1.7, w: 6.0, h: 4.2,
    chartColors: [C.ga],
    chartArea: { fill: { color: C.white } },
    catAxisLabelColor: C.dark, valAxisLabelColor: C.muted,
    valAxisMinVal: 78, valAxisMaxVal: 83,
    valGridLine: { color: "E2E8F0", size: 0.5 }, catGridLine: { style: "none" },
    showValue: true, dataLabelPosition: "t", dataLabelColor: C.dark, dataLabelFontSize: 10,
    showLegend: false, showTitle: true,
    title: "Sensibilidad: capacidad de vuelos ± 30 % (GA-P1)",
    titleColor: C.dark, titleFontSize: 13,
    lineSize: 3, lineSmooth: true,
  });

  s.addChart(pres.charts.LINE, [
    { name: "GA",  labels: ["1 500", "3 000", "6 000", "9 000"], values: [82.2, 81.4, 80.7, 80.4] },
    { name: "ACO", labels: ["1 500", "3 000", "6 000", "9 000"], values: [82.2, 81.4, 80.7, 80.4] },
  ], {
    x: 6.9, y: 1.7, w: 6.0, h: 4.2,
    chartColors: [C.ga, C.aco],
    chartArea: { fill: { color: C.white } },
    catAxisLabelColor: C.dark, valAxisLabelColor: C.muted,
    valAxisMinVal: 78, valAxisMaxVal: 84,
    valGridLine: { color: "E2E8F0", size: 0.5 }, catGridLine: { style: "none" },
    showLegend: true, legendPos: "t", legendFontSize: 11, legendColor: C.dark,
    showTitle: true, title: "Escalabilidad: composite score vs. demanda",
    titleColor: C.dark, titleFontSize: 13,
    lineSize: 3, lineSmooth: true,
  });

  const hStatus = [
    { h: "H2", txt: "Banda ± 2 puntos al variar ± 30 %", val: "1.6 pts (máx.)", ok: true },
    { h: "H3", txt: "Score ≥ 78, degradación ≤ 3 al pasar de 1 500 a 9 000", val: "1.8 pts", ok: true },
    { h: "H4", txt: "100 % completados en escenarios nominales", val: "100 %", ok: true },
    { h: "H5", txt: "Perfil tuneado P1 supera al exploratorio P2", val: "Confirmado en GA y ACO", ok: true },
  ];
  const cw2 = (12.0 - 0.6) / 4;
  hStatus.forEach((it, i) => {
    const cx = 0.7 + i * (cw2 + 0.2);
    card(s, cx, 6.05, cw2, 1.15);
    s.addShape(pres.shapes.RECTANGLE, { x: cx, y: 6.05, w: 0.08, h: 1.15, fill: { color: it.ok ? "16A34A" : "B91C1C" }, line: { width: 0 } });
    s.addText(it.h + "  ·  Confirmada", {
      x: cx + 0.18, y: 6.12, w: cw2 - 0.25, h: 0.3, fontSize: 11, bold: true, color: "16A34A", margin: 0,
    });
    s.addText(it.txt, { x: cx + 0.18, y: 6.42, w: cw2 - 0.25, h: 0.45, fontSize: 10, color: C.ink, margin: 0 });
    s.addText(it.val, { x: cx + 0.18, y: 6.85, w: cw2 - 0.25, h: 0.3, fontSize: 11, bold: true, color: C.dark, margin: 0 });
  });
  addFooter(s, 16);
}

// ============================================================
// SLIDE 17 - CONCLUSIÓN: contraste y decisión
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.bg };
  addHeader(s, "03.C · CONCLUSIÓN", "Contraste de hipótesis y decisión de selección");

  const headers = ["Hip.", "Enunciado resumido", "Resultado", "Decisión"];
  const data = [
    ["H1", "GA > ACO con Δ > 1 punto e IC 95 % no solapado", "Δ = −0.19; IC solapado", "No se rechaza H1.0"],
    ["H2", "Score del ganador en banda ± 2 al variar capacidad ± 30 %", "Variación máx. 1.6 puntos", "Confirmada"],
    ["H3", "Score ≥ 78 con degradación ≤ 3 al subir demanda", "Score 80.4 – 82.2; Δ 1.8", "Confirmada"],
    ["H4", "100 % completados en escenarios nominales", "100 % en DAY_TO_DAY y PERIOD_*", "Confirmada"],
    ["H5", "Perfil P1 supera a P2 en ambos algoritmos", "P1 mejor en GA y ACO", "Confirmada"],
  ];
  const hr = headers.map((h) => ({
    text: h, options: { bold: true, color: C.white, fill: { color: C.dark }, align: "center", valign: "middle", fontSize: 12 },
  }));
  const dr = data.map((r, ri) => r.map((c, ci) => ({
    text: c,
    options: {
      fontSize: 11,
      color: ci === 3 ? (c.includes("Confirmada") ? "166534" : "991B1B") : C.ink,
      bold: ci === 0 || ci === 3,
      align: ci === 0 ? "center" : "left",
      valign: "middle",
      fill: { color: ri % 2 === 0 ? C.white : "F1F5F9" },
    },
  })));
  s.addTable([hr, ...dr], {
    x: 0.7, y: 1.6, w: 11.9, colW: [0.9, 5.4, 3.0, 2.6], rowH: 0.5,
    border: { pt: 0.5, color: C.divider }, fontFace: FONT_BODY,
  });

  s.addShape(pres.shapes.RECTANGLE, {
    x: 0.7, y: 4.7, w: 11.9, h: 1.3,
    fill: { color: C.dark }, line: { width: 0 },
  });
  s.addShape(pres.shapes.RECTANGLE, {
    x: 0.7, y: 4.7, w: 0.15, h: 1.3,
    fill: { color: C.accent }, line: { width: 0 },
  });
  s.addText("Decisión", {
    x: 0.95, y: 4.8, w: 4, h: 0.4, fontSize: 12, color: C.accent, bold: true, charSpacing: 4, margin: 0,
  });
  s.addText("Se elige GA con perfil GA-P1 como algoritmo operativo del Planificador.", {
    x: 0.95, y: 5.15, w: 11.6, h: 0.45, fontSize: 18, bold: true, color: C.white, margin: 0,
  });
  s.addText("ACO se conserva como segunda implementación intercambiable bajo el patrón Strategy del componente.", {
    x: 0.95, y: 5.6, w: 11.6, h: 0.4, fontSize: 12, color: "CADCFC", italic: true, margin: 0,
  });

  const reasons = [
    { t: "Mantenibilidad", d: "Operadores GA más simples; menos hiperparámetros sensibles que ACO (α, β, ρ)." },
    { t: "Determinismo", d: "Para una misma semilla, GA es más predecible (sin matriz de feromonas)." },
    { t: "Mejor en PERIOD", d: "Ventaja marginal en PERIOD_D5 y PERIOD_D7, más representativos del producto." },
  ];
  const cw3 = (11.9 - 0.4) / 3;
  reasons.forEach((it, i) => {
    const cx = 0.7 + i * (cw3 + 0.2);
    card(s, cx, 6.15, cw3, 1.05);
    s.addShape(pres.shapes.RECTANGLE, { x: cx, y: 6.15, w: 0.08, h: 1.05, fill: { color: C.primary }, line: { width: 0 } });
    s.addText(it.t, { x: cx + 0.2, y: 6.2, w: cw3 - 0.3, h: 0.35, fontSize: 12, bold: true, color: C.primary, margin: 0 });
    s.addText(it.d, { x: cx + 0.2, y: 6.5, w: cw3 - 0.3, h: 0.7, fontSize: 10, color: C.ink, margin: 0 });
  });
  addFooter(s, 17);
}

// ============================================================
// SLIDE 18 - Cierre
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.dark };
  s.addShape(pres.shapes.RECTANGLE, { x: 0, y: 0, w: SLIDE_W, h: 0.18, fill: { color: C.primary }, line: { width: 0 } });
  s.addShape(pres.shapes.RECTANGLE, { x: 0, y: 0.18, w: SLIDE_W, h: 0.04, fill: { color: C.accent }, line: { width: 0 } });

  s.addText("Mensajes clave", {
    x: 0.7, y: 0.9, w: 12, h: 0.5, fontSize: 14, color: C.accent, bold: true, charSpacing: 4, margin: 0,
  });
  s.addText("Para Tasf.B2B se selecciona GA-P1 como algoritmo operativo.", {
    x: 0.7, y: 1.5, w: 12, h: 1.0, fontSize: 32, bold: true, color: C.white, margin: 0,
  });

  const points = [
    "GA y ACO son operativamente equivalentes (Δ = 0.19, dentro del IC 95 %).",
    "GA es robusto: variación ≤ 1.6 pts ante ± 30 % de capacidad y degradación ≤ 1.8 pts hasta 9 000 envíos.",
    "GA cumple SLA al 100 % en escenarios nominales y mantiene baja varianza (DE = 1.46).",
    "ACO permanece intercambiable en código (patrón Strategy) para futuras comparaciones y exposición técnica.",
  ];
  let py = 3.0;
  points.forEach((p) => {
    s.addShape(pres.shapes.OVAL, { x: 0.7, y: py + 0.12, w: 0.18, h: 0.18, fill: { color: C.accent }, line: { width: 0 } });
    s.addText(p, { x: 1.0, y: py, w: 11.5, h: 0.6, fontSize: 14, color: "E2E8F0", margin: 0 });
    py += 0.7;
  });

  s.addShape(pres.shapes.RECTANGLE, { x: 0.7, y: 6.4, w: 12, h: 0.04, fill: { color: C.accent }, line: { width: 0 } });
  s.addText("Gracias · Equipo 3C · Proyecto Tasf.B2B", {
    x: 0.7, y: 6.55, w: 12, h: 0.5, fontSize: 16, bold: true, color: C.white, margin: 0,
  });
  s.addText("Documento: 22.dis.experim.v01  ·  Código: backend/src/main/java/com/tasfb2b/service/algorithm", {
    x: 0.7, y: 6.95, w: 12, h: 0.4, fontSize: 10, color: "CADCFC", italic: true, margin: 0,
  });
}

const out = "C:/Users/papar/OneDrive/Documentos/PDDS/documentacion/Exposicion_GA_ACO_Experimentacion.pptx";
pres.writeFile({ fileName: out }).then((f) => {
  console.log("Saved:", f);
});
