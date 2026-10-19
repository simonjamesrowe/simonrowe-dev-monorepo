"""Exercise the real pinned Alloy pipeline against local OTLP HTTP sinks only."""
import copy
import gzip
import http.server
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "grafana/alloy:v1.19.2"
SECRET = "PRIVATE-CONTENT-MUST-NOT-LEAVE"
received = []


class Sink(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        data = self.rfile.read(int(self.headers["Content-Length"]))
        if self.headers.get("Content-Encoding") == "gzip":
            data = gzip.decompress(data)
        received.append((self.path, json.loads(data)))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.STDOUT).strip()


def attrs(**values):
    return [{"key": key, "value": {"stringValue": value}} for key, value in values.items()]


def spans(payload):
    return [span for resource in payload["resourceSpans"]
            for scope in resource["scopeSpans"] for span in scope["spans"]]


def wait_for(condition, description):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.1)
    raise AssertionError(description)


def exercise(sink_port, enabled=True, key="test-key", expiry=None, percentage=100):
    received.clear()
    expiry = int(time.time()) + 60 if expiry is None else expiry
    name = "honeycomb-test-" + uuid.uuid4().hex[:12]
    # Reuse the real production AI filter, not a duplicated expression in a fixture.
    prod = (ROOT / "config/alloy/config.alloy").read_text()
    ai_filter = prod[prod.index('otelcol.processor.filter "ai_only"'):prod.index('\n/*')]
    config = f'''
import.file "honeycomb" {{ filename = "/etc/alloy/honeycomb.alloy" }}
honeycomb.pipeline "backend" {{
  enabled = {str(enabled).lower()}
  api_key = {json.dumps(key)}
  endpoint = "http://host.docker.internal:{sink_port}/honeycomb"
  environment = "test"
  sample_percentage = {percentage}
  export_until = {expiry}
}}
otelcol.receiver.otlp "test" {{
  http {{ endpoint = "0.0.0.0:4318" }}
  output {{ traces = [honeycomb.pipeline.backend.input, otelcol.processor.filter.ai_only.input] }}
}}
{ai_filter}
otelcol.exporter.otlphttp "langfuse" {{
  encoding = "json"
  client {{ endpoint = "http://host.docker.internal:{sink_port}/langfuse" }}
}}
'''
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "test.alloy"
        fixture.write_text(config)
        try:
            docker("run", "-d", "--name", name, "--add-host", "host.docker.internal:host-gateway",
                   "-p", "127.0.0.1::4318", "-v", f"{fixture}:/etc/alloy/test.alloy:ro",
                   "-v", f"{ROOT / 'config/alloy/honeycomb.alloy'}:/etc/alloy/honeycomb.alloy:ro",
                   IMAGE, "run", "/etc/alloy/test.alloy")
            port = docker("port", name, "4318").rsplit(":", 1)[1]
            url = f"http://127.0.0.1:{port}/v1/traces"
            now = time.time_ns()
            parent = {
                "traceId": "0123456789abcdef0123456789abcdef", "spanId": "0123456789abcdef",
                "name": SECRET, "kind": 2, "startTimeUnixNano": str(now - 1000000),
                "endTimeUnixNano": str(now),
                "attributes": attrs(**{"http.route": "/api/coparent/events/{id}",
                                       "http.request.method": "GET", "url.full": SECRET,
                                       "langfuse.trace.input": SECRET}),
                "status": {"code": 2, "message": SECRET},
                "events": [{"name": SECRET, "timeUnixNano": str(now),
                            "attributes": attrs(**{"exception.message": SECRET})}],
                "traceState": "private=" + SECRET,
            }
            child = copy.deepcopy(parent)
            child.update(spanId="1123456789abcdef", parentSpanId=parent["spanId"], kind=1,
                         attributes=attrs(**{"gen_ai.operation.name": "chat",
                                             "gen_ai.request.model": "test-model",
                                             "langfuse.observation.input": SECRET}))
            health = copy.deepcopy(parent)
            health.update(spanId="2123456789abcdef", attributes=attrs(**{"http.route": "/actuator/health"}))
            linked = copy.deepcopy(parent)
            linked.update(spanId="3123456789abcdef", links=[{"traceId": parent["traceId"],
                          "spanId": parent["spanId"], "attributes": attrs(private=SECRET)}])
            payload = {"resourceSpans": [{
                "resource": {"attributes": attrs(**{"service.name": "simonrowe-backend",
                                                     "service.version": "abc123", "private": SECRET})},
                "scopeSpans": [{"scope": {"name": "test", "attributes": attrs(private=SECRET)},
                                "spans": [parent, child, health, linked]}],
            }]}

            if percentage != 100:
                # Different requests retain/drop together even when each trace has two spans.
                sampled_payload = []
                for number in range(200):
                    for original in (parent, child):
                        item = copy.deepcopy(original)
                        item["traceId"] = f"{number + 1:032x}"
                        sampled_payload.append(item)
                payload["resourceSpans"][0]["scopeSpans"][0]["spans"] = sampled_payload

            def send():
                try:
                    request = urllib.request.Request(url, json.dumps(payload).encode(),
                                                     {"Content-Type": "application/json"})
                    with urllib.request.urlopen(request, timeout=2) as response:
                        assert response.status == 200
                    return True
                except (urllib.error.URLError, OSError):
                    return False

            wait_for(send, "Alloy did not accept OTLP")
            wait_for(lambda: any(p.startswith("/langfuse/") for p, _ in received),
                     "Langfuse sibling branch received nothing")
            active = enabled and bool(key) and expiry > time.time() and percentage > 0
            if active:
                wait_for(lambda: any(p.startswith("/honeycomb/") for p, _ in received),
                         "Honeycomb received nothing")
                honey = [body for path, body in received if path.startswith("/honeycomb/")]
                assert SECRET not in json.dumps(honey), json.dumps(honey)
                exported = [span for body in honey for span in spans(body)]
                if percentage != 100:
                    by_trace = {}
                    for span in exported:
                        by_trace.setdefault(span["traceId"], []).append(span)
                        weight = next(a["value"] for a in span["attributes"] if a["key"] == "SampleRate")
                        assert float(next(iter(weight.values()))) == 100 / percentage
                    assert 0 < len(by_trace) < 200
                    assert all(len(pair) == 2 for pair in by_trace.values())
                    exported = next(iter(by_trace.values()))
                assert len(exported) == 2, exported
                assert {s["spanId"] for s in exported} == {parent["spanId"], child["spanId"]}
                actual_child = next(s for s in exported if s["spanId"] == child["spanId"])
                assert actual_child["parentSpanId"] == parent["spanId"]
                assert actual_child["traceId"] == exported[0]["traceId"]
                assert actual_child["name"] == "chat"
                actual_parent = next(s for s in exported if s["spanId"] == parent["spanId"])
                values = {a["key"]: a["value"] for a in actual_parent["attributes"]}
                assert values["app.module"]["stringValue"] == "coparent"
                assert actual_parent["status"]["code"] == 2
            else:
                time.sleep(1.5)  # Beyond the actual 1s batch flush; negative export assertion.
                assert not any(p.startswith("/honeycomb/") for p, _ in received)
            langfuse = [body for path, body in received if path.startswith("/langfuse/")]
            assert SECRET in json.dumps(langfuse), "Honeycomb mutated the Langfuse sibling"
            assert len([s for body in langfuse for s in spans(body)]) == (1 if percentage == 100 else 200)
            logs = docker("logs", name)
            assert 'failed to execute' not in logs.lower(), logs
        except Exception:
            print(docker("logs", name))
            raise
        finally:
            subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), Sink)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        for config in ("config.alloy", "config.local.alloy"):
            docker("run", "--rm", "-v", f"{ROOT / 'config/alloy'}:/etc/alloy:ro",
                   IMAGE, "validate", f"/etc/alloy/{config}")
        for settings in ({}, {"percentage": 10}, {"percentage": 0},
                         {"enabled": False, "percentage": 0},
                         {"enabled": False}, {"key": ""}, {"expiry": 1}):
            exercise(server.server_port, **settings)
            print(f"PASS Honeycomb pipeline {settings or 'sanitization and parentage'}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
