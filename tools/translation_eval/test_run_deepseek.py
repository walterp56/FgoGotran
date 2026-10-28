import copy
import unittest

import evaluate as ev
import run_deepseek as runner


class DeepSeekCollectorTest(unittest.TestCase):
    def test_request_matches_app_defaults_without_persisting_credentials(self):
        request = {"id": "case:en", "messages": [{"role": "system", "content": "Translate."}, {"role": "user", "content": "はい。"}],
                   "prompt_sha256": "hash"}
        parameters = {"max_tokens": 256, "temperature": 1.0, "thinking": {"type": "disabled"}}
        seen = []

        def transport(payload, key, timeout):
            seen.append((copy.deepcopy(payload), key, timeout))
            return 200, {"model": "reported-model", "choices": [{"message": {"content": "Yes."}, "finish_reason": "stop"}],
                         "usage": {"prompt_tokens": 42, "completion_tokens": 2, "prompt_cache_hit_tokens": 20}}, 123.5

        run = runner.collect(request, "baseline", "deepseek-v4-flash", parameters, 1, "synthetic-secret", 30, transport)
        self.assertEqual({"model": "deepseek-v4-flash", "messages": request["messages"], **parameters}, seen[0][0])
        self.assertNotIn("synthetic-secret", ev.canonical(run))
        self.assertEqual("Yes.", run["attempts"][0]["response"])
        self.assertEqual(42, run["attempts"][0]["input_tokens"])
        self.assertEqual(123.5, run["attempts"][0]["elapsed_ms"])

    def test_failed_http_remains_a_recorded_empty_response(self):
        run = runner.collect({"id": "x:en", "messages": [], "prompt_sha256": "hash"}, "baseline", "deepseek-v4-flash",
                             {}, 1, "synthetic", 30, lambda *args: (429, {}, 10))
        self.assertEqual("", run["attempts"][0]["response"])
        self.assertEqual(429, run["attempts"][0]["http_status"])
        self.assertIsNone(run["attempts"][0]["input_tokens"])

    def test_scheduling_keeps_variants_adjacent_and_preserves_every_trial(self):
        requests = {"a:en": {"id": "a:en", "case_id": "a"}, "b:en": {"id": "b:en", "case_id": "b"}}
        exports = {variant: {"data": {"fixture_sha256": "same"}, "requests": requests} for variant in ("baseline", "candidate")}
        jobs = runner.schedule(exports, 3, 10)
        self.assertEqual(12, len(jobs))
        for index in range(0, len(jobs), 2):
            self.assertEqual({"baseline", "candidate"}, {jobs[index][0], jobs[index + 1][0]})
            self.assertEqual(jobs[index][1]["id"], jobs[index + 1][1]["id"])
            self.assertEqual(jobs[index][2], jobs[index + 1][2])
        self.assertEqual(6, len(runner.schedule(exports, 3, 10, ["a"])))


if __name__ == "__main__":
    unittest.main()
