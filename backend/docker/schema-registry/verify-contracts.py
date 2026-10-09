"""Verify the bootstrapped V1 catalog through the actual Registry HTTP API."""

import argparse
import json
import os
from urllib.parse import quote
from urllib.request import urlopen


def verify(url, topic):
    def get(path):
        with urlopen(url.rstrip("/") + path, timeout=5) as response:
            return json.load(response)

    for event in ("PullRequestOpened", "PullRequestMerged", "CommitCreated"):
        subject = topic + "-com.changeguard.events.code." + event
        encoded = quote(subject, safe="")
        policy = get("/config/" + encoded)["compatibilityLevel"]
        if policy != "BACKWARD_TRANSITIVE":
            raise RuntimeError(f"Unexpected compatibility policy for {subject}: {policy}")
        registered = get("/subjects/" + encoded + "/versions/latest")
        schema = json.loads(registered["schema"])
        name = schema["name"]
        fullname = name if "." in name else schema.get("namespace", "") + "." + name
        if fullname != "com.changeguard.events.code." + event:
            raise RuntimeError(f"Unexpected registered record for {subject}")
        if registered["id"] <= 0:
            raise RuntimeError(f"Missing schema ID for {subject}")
        print(f"Verified {subject}: schema ID {registered['id']}, {policy}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:8082"))
    parser.add_argument("--topic", default=os.environ.get("CODE_EVENTS_TOPIC", "changeguard.code-events.v1"))
    args = parser.parse_args()
    verify(args.url, args.topic)
