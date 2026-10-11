"""
Static tests for docker-compose.yml port bindings.

Parses docker-compose.yml with PyYAML (no Docker invocation).
Every host-published port must have a 127.0.0.1: prefix.
"""
import os
import yaml
import pytest

COMPOSE_PATH = os.path.join(os.path.dirname(__file__), "..", "docker-compose.yml")


def _load_compose():
    with open(COMPOSE_PATH) as f:
        return yaml.safe_load(f)


def _host_ip_from_entry(entry):
    """Return the host IP from a ports entry (string or dict form).

    String form: "host_ip:host_port:container_port" or "host_port:container_port"
    Dict form:   {published: ..., target: ..., host_ip: ...}
    """
    if isinstance(entry, dict):
        return entry.get("host_ip", "")
    # string form
    parts = str(entry).split(":")
    if len(parts) >= 3:
        return parts[0]
    # short form "host_port:container_port" — no host_ip
    return ""


def test_frontend_port_bound_to_loopback():
    compose = _load_compose()
    frontend = compose["services"]["frontend"]
    assert frontend["ports"] == ["127.0.0.1:5173:80"]


def test_no_service_publishes_without_loopback_prefix():
    compose = _load_compose()
    services = compose.get("services", {})
    violations = []
    for svc_name, svc in services.items():
        for entry in svc.get("ports", []):
            host_ip = _host_ip_from_entry(entry)
            if host_ip != "127.0.0.1":
                violations.append((svc_name, entry))
    assert violations == [], (
        "Services publishing without 127.0.0.1 host prefix: "
        + ", ".join(f"{s}: {e}" for s, e in violations)
    )
