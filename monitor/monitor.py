import datetime
import json
import os
import socket
import sys
import time

PROC_ROOT = os.environ.get("PROC_ROOT", "/host/proc")
DISK_PATH = os.environ.get("DISK_PATH", "/host")
DOCKER_SOCKET = os.environ.get("DOCKER_SOCKET", "/var/run/docker.sock")
LOG_PATH = os.environ.get("LOG_PATH", "/logs/monitor.jsonl")
INTERVAL = int(os.environ.get("MONITOR_INTERVAL_SECONDS", "60"))


def read_cpu(proc_root: str = PROC_ROOT) -> float | None:
    stat_path = os.path.join(proc_root, "stat")
    try:
        with open(stat_path) as f:
            line1 = f.readline()
    except OSError:
        return None

    time.sleep(1)

    try:
        with open(stat_path) as f:
            line2 = f.readline()
    except OSError:
        return None

    def parse_cpu_line(line):
        parts = line.split()
        # parts[0] is 'cpu', rest are: user nice sys idle iowait irq softirq steal guest guest_nice
        vals = [int(x) for x in parts[1:]]
        total = sum(vals)
        idle = vals[3]
        return idle, total

    idle1, total1 = parse_cpu_line(line1)
    idle2, total2 = parse_cpu_line(line2)

    delta_idle = idle2 - idle1
    delta_total = total2 - total1

    if delta_total == 0:
        return 0.0

    return round(100 * (1 - delta_idle / delta_total), 1)


def read_ram(proc_root: str = PROC_ROOT) -> dict | None:
    meminfo_path = os.path.join(proc_root, "meminfo")
    try:
        with open(meminfo_path) as f:
            content = f.read()
    except OSError:
        return None

    values = {}
    for line in content.splitlines():
        if line.startswith("MemTotal:"):
            values["MemTotal"] = int(line.split()[1])
        elif line.startswith("MemAvailable:"):
            values["MemAvailable"] = int(line.split()[1])

    if "MemTotal" not in values or "MemAvailable" not in values:
        return None

    ram_total_mb = values["MemTotal"] // 1024
    ram_used_mb = (values["MemTotal"] - values["MemAvailable"]) // 1024
    return {"ram_used_mb": ram_used_mb, "ram_total_mb": ram_total_mb}


def read_disk(disk_path: str = DISK_PATH) -> dict | None:
    try:
        st = os.statvfs(disk_path)
    except OSError:
        return None

    used_bytes = (st.f_blocks - st.f_bavail) * st.f_frsize
    free_bytes = st.f_bavail * st.f_frsize

    disk_used_gb = round(used_bytes / 1_000_000_000, 1)
    disk_free_gb = round(free_bytes / 1_000_000_000, 1)
    return {"disk_used_gb": disk_used_gb, "disk_free_gb": disk_free_gb}


def read_network(proc_root: str = PROC_ROOT) -> dict | None:
    net_dev_path = os.path.join(proc_root, "net", "dev")
    try:
        with open(net_dev_path) as f:
            content = f.read()
    except OSError:
        return None

    rx_total = 0
    tx_total = 0

    for line in content.splitlines():
        line = line.strip()
        if ":" not in line:
            continue
        iface, data = line.split(":", 1)
        iface = iface.strip()
        if iface == "lo":
            continue
        parts = data.split()
        if len(parts) < 9:
            continue
        rx_total += int(parts[0])
        tx_total += int(parts[8])

    net_rx_mb = round(rx_total / 1048576, 1)
    net_tx_mb = round(tx_total / 1048576, 1)
    return {"net_rx_mb": net_rx_mb, "net_tx_mb": net_tx_mb}


def _http_get_unix(socket_path: str, path: str) -> bytes:
    """Perform a raw HTTP GET over a Unix domain socket. Returns response body."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.connect(socket_path)
        request = f"GET {path} HTTP/1.0\r\nHost: localhost\r\n\r\n"
        sock.sendall(request.encode())

        chunks = []
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            chunks.append(chunk)

    raw = b"".join(chunks)
    # Split headers from body
    header_end = raw.find(b"\r\n\r\n")
    if header_end == -1:
        return raw
    return raw[header_end + 4:]


def read_containers(socket_path: str = DOCKER_SOCKET) -> dict:
    try:
        body = _http_get_unix(socket_path, "/v1.43/containers/json")
        containers = json.loads(body)
    except Exception as e:
        print(f"WARNING: could not list containers: {e}", file=sys.stderr)
        return {}

    result = {}
    for container in containers:
        cid = container["Id"]
        name = container["Names"][0].lstrip("/")

        try:
            stats_body = _http_get_unix(socket_path, f"/v1.43/containers/{cid}/stats?stream=false")
            stats = json.loads(stats_body)
        except Exception as e:
            print(f"WARNING: could not get stats for {name}: {e}", file=sys.stderr)
            continue

        try:
            cpu_stats = stats["cpu_stats"]
            precpu_stats = stats["precpu_stats"]

            delta_cpu = (
                cpu_stats["cpu_usage"]["total_usage"]
                - precpu_stats["cpu_usage"]["total_usage"]
            )
            delta_system = (
                cpu_stats["system_cpu_usage"]
                - precpu_stats["system_cpu_usage"]
            )

            percpu = cpu_stats["cpu_usage"].get("percpu_usage")
            if percpu:
                num_cpus = len(percpu)
            else:
                num_cpus = cpu_stats.get("online_cpus")

            if num_cpus is None or delta_system == 0:
                cpu_pct = None
            else:
                cpu_pct = round((delta_cpu / delta_system) * num_cpus * 100, 1)
        except (KeyError, ZeroDivisionError):
            cpu_pct = None

        try:
            ram_mb = stats["memory_stats"]["usage"] // 1048576
        except (KeyError, TypeError):
            ram_mb = None

        result[name] = {"cpu_pct": cpu_pct, "ram_mb": ram_mb}

    return result


def collect_sample() -> dict:
    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    cpu = read_cpu()
    ram = read_ram()
    disk = read_disk()
    network = read_network()
    containers = read_containers()

    sample = {
        "ts": ts,
        "cpu_pct": cpu,
        "ram_used_mb": ram["ram_used_mb"] if ram is not None else None,
        "ram_total_mb": ram["ram_total_mb"] if ram is not None else None,
        "disk_used_gb": disk["disk_used_gb"] if disk is not None else None,
        "disk_free_gb": disk["disk_free_gb"] if disk is not None else None,
        "net_rx_mb": network["net_rx_mb"] if network is not None else None,
        "net_tx_mb": network["net_tx_mb"] if network is not None else None,
        "containers": containers,
    }
    return sample


def main() -> None:
    os.makedirs("/logs", exist_ok=True)

    try:
        f = open(LOG_PATH, "a")
    except OSError as e:
        print(f"ERROR: cannot open log file {LOG_PATH}: {e}", file=sys.stderr)
        sys.exit(1)

    with f:
        while True:
            sample = collect_sample()
            f.write(json.dumps(sample) + "\n")
            f.flush()
            time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
