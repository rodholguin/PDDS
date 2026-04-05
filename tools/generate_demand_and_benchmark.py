#!/usr/bin/env python3
import argparse
import csv
import json
import time
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen

BASE_URL = "http://localhost:8080"
OUT_DIR = Path("documentacion/datasets")
OUT_FILE = OUT_DIR / "demand_scenarios.csv"
RESULTS_CSV = Path("documentacion/benchmark-results.csv")
RESULTS_JSON = Path("documentacion/benchmark-results.json")
SUMMARY_MD = Path("documentacion/benchmark-summary.md")
JOB_FILE = Path("documentacion/benchmark-job.json")


def call(method: str, path: str):
    req = Request(BASE_URL + path, method=method)
    with urlopen(req, timeout=900) as response:
        return json.loads(response.read().decode("utf-8"))


def wait_backend_ready():
    for _ in range(120):
        try:
            call("GET", "/api/simulation/state")
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError("Backend no disponible en tiempo esperado")


def download_scenario_template():
    req = Request(BASE_URL + "/api/import/template/shipments-scenarios", method="GET")
    with urlopen(req, timeout=120) as response:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        OUT_FILE.write_bytes(response.read())


def start_benchmark_job():
    started = call("POST", "/api/import/benchmark/start")
    job_id = started.get("jobId")
    if not job_id:
        raise RuntimeError("No se recibió jobId para benchmark")
    return job_id

def poll_benchmark(job_id: str, max_wait_seconds: int):
    loops = max(1, max_wait_seconds // 2)
    for _ in range(loops):
        status = call("GET", f"/api/import/benchmark/status/{job_id}")
        print(f"Estado benchmark [{job_id[:8]}]: {status.get('status')} - {status.get('message')}")
        if status.get("status") in {"DONE", "FAILED"}:
            if status.get("status") == "FAILED":
                raise RuntimeError(status.get("message") or "Benchmark falló")
            return status.get("result") or {}
        time.sleep(2)
    return None


def write_results_csv(rows: list[dict]):
    RESULTS_CSV.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        RESULTS_CSV.write_text("", encoding="utf-8")
        return
    fieldnames = [
        "profileName",
        "algorithm",
        "scenario",
        "demandSize",
        "seed",
        "createdShipments",
        "cancelledFlights",
        "replanned",
        "delivered",
        "completedPct",
        "avgTransitHours",
        "deadlineMissRate",
        "operationalCost",
        "flightUtilizationPct",
        "saturatedAirports",
        "replanSuccessPct",
        "compositeScore",
    ]
    with RESULTS_CSV.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k) for k in fieldnames})


def write_results_json(result: dict):
    RESULTS_JSON.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_JSON.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")


def write_summary_md(result: dict):
    best = result.get("bestProfile") or {}
    scenarios = result.get("scenarios") or []
    rows = result.get("rows") or []

    lines = []
    lines.append("# Benchmark Exhaustivo de Algoritmos")
    lines.append("")
    lines.append(f"Generado: {datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"Ganador global: **{result.get('winner', 'N/A')}**")
    lines.append(f"Muestra total: {result.get('sampleSize', 0)} corridas")
    lines.append("")
    lines.append("## Defaults aplicados")
    lines.append("- Semillas por escenario: 5")
    lines.append("- Tamaños de demanda: small/medium/large")
    lines.append("- Ponderaciones: completed 30%, avgTransit 25%, deadlineMiss 20%, replanSuccess 10%, cost 10%, saturated 5%")
    lines.append("")
    lines.append("## Perfil ganador")
    lines.append(f"- Perfil: `{best.get('profile', {}).get('profileName', 'N/A')}`")
    lines.append(f"- Familia algoritmo: `{result.get('winner', 'N/A')}`")
    lines.append(f"- Score compuesto: `{best.get('totalScore', 0):.2f}`")
    lines.append(f"- Completed % promedio: `{best.get('completedPct', 0):.2f}`")
    lines.append(f"- Avg transit hours promedio: `{best.get('avgTransitHours', 0):.2f}`")
    lines.append(f"- Deadline miss rate promedio: `{best.get('deadlineMissRate', 0):.2f}`")
    lines.append(f"- Replan success % promedio: `{best.get('replanSuccessPct', 0):.2f}`")
    lines.append("")
    lines.append("## Fundamentos de elección")
    lines.append("- Maximiza score multi-criterio ponderado bajo escenarios normales, picos, colapso, disrupción y recuperación.")
    lines.append("- Prioriza cumplimiento y tiempo de tránsito sin ignorar resiliencia (replanificación) ni costo operacional.")
    lines.append("- Mantiene desempeño estable en diferentes tamaños de demanda y múltiples semillas.")
    lines.append("")
    lines.append("## Ganador por escenario")
    lines.append("| Escenario | Ganador | Completed % | Avg Transit h | Deadline Miss % | Score |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for s in scenarios:
        lines.append(
            f"| {s.get('scenario')} | {s.get('winner')} | {float(s.get('completedPct') or 0):.2f} | "
            f"{float(s.get('avgTransitHours') or 0):.2f} | {float(s.get('deadlineMissRate') or 0):.2f} | "
            f"{float(s.get('compositeScore') or 0):.2f} |"
        )

    lines.append("")
    lines.append("## Archivos de evidencia")
    lines.append(f"- Datos de demanda: `{OUT_FILE}`")
    lines.append(f"- Resultados exhaustivos tabulares: `{RESULTS_CSV}`")
    lines.append(f"- Resultado completo estructurado: `{RESULTS_JSON}`")
    lines.append("")
    lines.append("## Notas")
    lines.append(f"- Corridas registradas: {len(rows)}")
    lines.append("- El algoritmo elegido surge del score compuesto y no de una única métrica aislada.")

    SUMMARY_MD.parent.mkdir(parents=True, exist_ok=True)
    SUMMARY_MD.write_text("\n".join(lines), encoding="utf-8")


def parse_args():
    parser = argparse.ArgumentParser(description="Genera demanda y gestiona benchmark asíncrono")
    parser.add_argument("--collect", action="store_true", help="Solo consulta/recoge resultado de un job ya iniciado")
    parser.add_argument("--job-id", type=str, help="Job ID específico a consultar")
    parser.add_argument("--wait-seconds", type=int, default=60, help="Segundos máximos de espera en esta ejecución")
    parser.add_argument("--blocking", action="store_true", help="Esperar hasta terminar (puede tardar mucho)")
    return parser.parse_args()


def main():
    args = parse_args()
    wait_backend_ready()

    if args.collect:
        if args.job_id:
            job_id = args.job_id
        elif JOB_FILE.exists():
            job_id = json.loads(JOB_FILE.read_text(encoding="utf-8")).get("jobId")
        else:
            latest = call("GET", "/api/import/benchmark/status")
            job_id = latest.get("jobId")
            if not job_id:
                raise RuntimeError("No hay jobId disponible. Usa primero el modo inicio.")
            JOB_FILE.parent.mkdir(parents=True, exist_ok=True)
            JOB_FILE.write_text(json.dumps({"jobId": job_id}, indent=2), encoding="utf-8")
    else:
        stop_result = call("POST", "/api/simulation/reset-demand")
        print(stop_result.get("message"))

        download_scenario_template()
        print(f"Archivo de demanda generado: {OUT_FILE}")

        job_id = start_benchmark_job()
        JOB_FILE.parent.mkdir(parents=True, exist_ok=True)
        JOB_FILE.write_text(json.dumps({"jobId": job_id}, indent=2), encoding="utf-8")
        print(f"Benchmark iniciado. jobId={job_id}")

    wait_seconds = 120000 if args.blocking else args.wait_seconds
    result = poll_benchmark(job_id, wait_seconds)
    if result is None:
        print("Benchmark sigue en RUNNING. Vuelve a ejecutar con --collect para continuar el monitoreo.")
        return

    print("Benchmark ejecutado. Ganador:", result.get("winner"))
    write_results_csv(result.get("rows") or [])
    write_results_json(result)
    write_summary_md(result)
    print(f"Resultados CSV: {RESULTS_CSV}")
    print(f"Resultados JSON: {RESULTS_JSON}")
    print(f"Resumen MD: {SUMMARY_MD}")


if __name__ == "__main__":
    main()
