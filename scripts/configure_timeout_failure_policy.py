#!/usr/bin/env python3
"""Configure only ResQ's timeout failure routing; never reads credentials or recreates queues."""
import argparse
import json
import re
import subprocess

POLICY_NAME = "resq-timeout-failure"
QUEUE = "resq.reservation.timeout.dead.queue"
PATTERN = r"^resq\.reservation\.timeout\.dead\.queue$"
DEFINITION = {
    "dead-letter-exchange": "resq.reservation.timeout.failed.exchange",
    "dead-letter-routing-key": "reservation.timeout.failed",
}


def ctl(container, *args):
    result = subprocess.run(["docker", "exec", container, "rabbitmqctl", *args],
                            text=True, capture_output=True, check=True)
    return result.stdout


def check_existing(policies):
    for policy in policies:
        if policy["vhost"] != "/":
            continue
        if policy["name"] == POLICY_NAME:
            definition = policy["definition"]
            if isinstance(definition, str):
                definition = json.loads(definition)
            if (policy["pattern"] != PATTERN
                    or definition != DEFINITION
                    or policy["apply-to"] != "queues"
                    or int(policy["priority"]) != 10):
                raise RuntimeError("Existing policy with this name differs; review it before changing.")
        elif (policy.get("apply-to") in ("queues", "all")
              and re.search(policy["pattern"], QUEUE)):
            raise RuntimeError("Another policy matches the queue; review precedence before changing.")


def configure(container, apply=False):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", container):
        raise ValueError("Specify an explicit Docker container name or ID.")
    policies = json.loads(ctl(container, "--formatter=json", "list_policies"))
    check_existing(policies)
    print(json.dumps({"container": container, "vhost": "/", "name": POLICY_NAME,
                      "pattern": PATTERN, "apply-to": "queues", "priority": 10,
                      "definition": DEFINITION}, indent=2))
    if not apply:
        print("Preview only. Add --apply to set this policy; no queues or messages are deleted.")
        return
    ctl(container, "set_policy", "--vhost", "/", "--priority", "10", "--apply-to", "queues",
        POLICY_NAME, PATTERN, json.dumps(DEFINITION, separators=(",", ":")))
    policies = json.loads(ctl(container, "--formatter=json", "list_policies"))
    check_existing(policies)
    if not any(p["name"] == POLICY_NAME and p["vhost"] == "/" for p in policies):
        raise RuntimeError("Policy was not found after applying.")
    print("Policy verified. Existing queue declarations and messages were preserved.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    configure(args.container, args.apply)
