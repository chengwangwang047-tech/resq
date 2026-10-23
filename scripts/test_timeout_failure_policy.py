#!/usr/bin/env python3
import copy
import contextlib
import io
import json
import unittest
from unittest.mock import patch
import configure_timeout_failure_policy as policy


class PolicySafetyTest(unittest.TestCase):
    def expected(self):
        return {"vhost": "/", "name": policy.POLICY_NAME, "pattern": policy.PATTERN,
                "definition": dict(policy.DEFINITION), "apply-to": "queues", "priority": 10}

    def test_existing_expected_policy_can_be_reapplied(self):
        policy.check_existing([self.expected()])
        existing = self.expected()
        existing["definition"] = json.dumps(existing["definition"])
        policy.check_existing([existing])

    def test_different_policy_with_same_name_is_not_overwritten(self):
        existing = self.expected()
        existing["definition"] = {"dead-letter-exchange": "another-exchange"}
        with self.assertRaises(RuntimeError):
            policy.check_existing([existing])

    def test_overlapping_policy_is_not_silently_replaced(self):
        existing = self.expected()
        existing.update(name="another-policy", pattern=r"^resq\.")
        with self.assertRaises(RuntimeError):
            policy.check_existing([existing])

    def test_unrelated_queue_policy_is_preserved(self):
        existing = self.expected()
        existing.update(name="unrelated", pattern="^another-queue$")
        policy.check_existing([existing])

    def test_preview_does_not_mutate_broker(self):
        with patch.object(policy, "ctl", return_value="[]") as ctl, contextlib.redirect_stdout(io.StringIO()):
            policy.configure("explicit-test-container")
        self.assertEqual(ctl.call_count, 1)
        self.assertEqual(ctl.call_args.args[1:], ("--formatter=json", "list_policies"))

    def test_container_argument_cannot_be_an_option_or_shell_fragment(self):
        with patch.object(policy, "ctl") as ctl:
            for value in ("--help", "container; echo bad", ""):
                with self.assertRaises(ValueError):
                    policy.configure(value, True)
            ctl.assert_not_called()

    def test_apply_verifies_policy_was_recorded(self):
        with patch.object(policy, "ctl", side_effect=["[]", "", "[]"]), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "not found"):
                policy.configure("explicit-test-container", True)


if __name__ == "__main__":
    unittest.main()
