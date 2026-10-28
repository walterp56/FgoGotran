"""Synthetic harness tests. These do not measure a translation model."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

import evaluate as ev


class OfflineEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture_hash = ev.digest(ev.ROOT / "cases.json")
        self.exports = {}
        self.export_paths = []
        for variant in ("baseline", "candidate"):
            messages = [{"role": "system", "content": "Translate faithfully." + (" Keep register." if variant == "candidate" else "")},
                        {"role": "user", "content": "まだ、まだ……！"}]
            request = {"id": "source_repetition:en", "case_id": "source_repetition", "target_language": "en",
                       "mode": "dialogue", "messages": messages, "prompt_sha256": ev.prompt_hash(messages),
                       "input": {"source": "まだ、まだ……！"}, "checks": ["Preserve repetition."]}
            path = self.save(variant + ".json", {"schema_version": 1, "scope": "general_non_sakura", "variant": variant,
                                                 "fixture_sha256": self.fixture_hash, "requests": [request]})
            self.export_paths.append(path)
        self.exports = ev.load_exports(self.export_paths)
        self.runs = [self.run_for(variant) for variant in self.exports]
        self.responses = self.save("responses.json", {"schema_version": 1, "fixture_sha256": self.fixture_hash, "runs": self.runs})

    def save(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def run_for(self, variant):
        return {"id": variant + "-run", "variant": variant, "request_id": "source_repetition:en",
                "prompt_sha256": self.exports[variant]["requests"]["source_repetition:en"]["prompt_sha256"],
                "backend": "synthetic", "model": "synthetic-model", "parameters": {"temperature": 0},
                "trial": 1, "cache_status": "bypassed", "latency_scope": "backend_request_sequence",
                "attempts": [{"response": "Still... still!"}]}

    def validation(self):
        return self.save("validation.json", {"schema_version": 1, "responses_sha256": ev.digest(self.responses),
            "runs": [{"id": run["id"], "attempts": [{"valid": True} for _ in run["attempts"]], "final_valid": True}
                     for run in self.runs]})

    def completed_review(self):
        packet, key = ev.blind_packet(self.runs, self.exports, self.responses, 10)
        for entry in packet["entries"]:
            entry["scores"] = {name: 4 for name in ev.DIMENSIONS}
            entry["critical_failure"] = False
        review_path, key_path = self.save("review.json", packet), self.save("key.json", key)
        return ev.checked_reviews(review_path, key_path, self.responses, self.runs)

    def report(self, reviews=None):
        return ev.make_report(self.runs, self.exports, self.validation(), self.responses, reviews or {})

    def test_real_fixture_and_pending_ledger_are_valid(self):
        cases = ev.check_cases(ev.ROOT / "cases.json")
        self.assertEqual(45, len(cases["cases"]) * len(cases["targets"]))
        ledger = ev.check_evidence(ev.ROOT / "evidence.json", cases)
        self.assertTrue(all(item["status"] != "approved" for item in ledger["items"]))

    def test_duplicate_cases_and_incomplete_targets_are_rejected(self):
        cases = ev.read(ev.ROOT / "cases.json")
        cases["cases"].append(copy.deepcopy(cases["cases"][0]))
        with self.assertRaises(ValueError):
            ev.check_cases(self.save("bad_cases.json", cases))
        cases["cases"].pop()
        cases["targets"] = ["en"]
        with self.assertRaises(ValueError):
            ev.check_cases(self.save("bad_targets.json", cases))

    def test_unverified_evidence_cannot_be_marked_approved(self):
        ledger = ev.read(ev.ROOT / "evidence.json")
        ledger["items"][0]["status"] = "approved"
        with self.assertRaises(ValueError):
            ev.check_evidence(self.save("unverified.json", ledger), ev.check_cases(ev.ROOT / "cases.json"))

    def test_blind_packet_hides_versions_models_hashes_and_run_ids(self):
        packet, key = ev.blind_packet(self.runs, self.exports, self.responses, 9)
        public = ev.canonical(packet)
        for hidden in ("baseline", "candidate", "synthetic-model", "prompt_sha256", "baseline-run"):
            self.assertNotIn(hidden, public)
        self.assertEqual({run["id"] for run in self.runs}, set(key["mapping"].values()))
        self.assertEqual((packet, key), ev.blind_packet(self.runs, self.exports, self.responses, 9))

    def test_edited_blind_source_or_response_is_rejected(self):
        packet, key = ev.blind_packet(self.runs, self.exports, self.responses, 9)
        packet["entries"][0]["response"] = "Edited translation"
        with self.assertRaises(ValueError):
            ev.checked_reviews(self.save("edited.json", packet), self.save("key.json", key), self.responses, self.runs)

    def test_pending_reviews_and_missing_times_are_not_zero_scores(self):
        report = self.report()
        self.assertEqual(0, report["fully_reviewed_matched_trials"])
        self.assertIsNone(report["automatic_winner"])
        for group in report["groups"]:
            self.assertEqual(0, group["reviewed"])
            self.assertIsNone(group["quality_means"]["meaning"])
            self.assertEqual({"n": 0, "p50": None, "p95": None}, group["latency_ms"])

    def test_fully_matched_reviews_count_and_unmatched_model_does_not(self):
        reviewed = self.completed_review()
        self.assertEqual(1, self.report(reviewed)["fully_reviewed_matched_trials"])
        self.runs[1]["model"] = "another-model"
        self.assertEqual(0, self.report(reviewed)["fully_reviewed_matched_trials"])

    def test_repairs_include_all_recorded_attempt_time_and_tokens(self):
        self.runs[0]["attempts"] = [
            {"response": "bad", "elapsed_ms": 100, "input_tokens": 20, "output_tokens": 3},
            {"response": "Still... still!", "elapsed_ms": 300, "input_tokens": 30, "output_tokens": 4}]
        baseline = next(group for group in self.report()["groups"] if group["variant"] == "baseline")
        self.assertEqual(1, baseline["runs_with_repair"])
        self.assertEqual(400, baseline["latency_ms"]["p50"])
        self.assertEqual(50, baseline["tokens_including_repairs"]["input_tokens"]["p50"])
        self.assertEqual(7, baseline["tokens_including_repairs"]["output_tokens"]["p50"])

    def test_partial_attempt_measurements_remain_unknown(self):
        self.runs[0]["attempts"] = [{"response": "bad", "elapsed_ms": 100}, {"response": "fixed"}]
        group = next(group for group in self.report()["groups"] if group["variant"] == "baseline")
        self.assertEqual(0, group["latency_ms"]["n"])

    def test_cache_and_application_times_are_separate_and_block_pairing(self):
        self.runs[1]["cache_status"] = "hit"
        self.runs[1]["latency_scope"] = "app_end_to_end"
        self.runs[1]["end_to_end_ms"] = 900
        self.runs[1]["attempts"][0]["elapsed_ms"] = 2
        report = self.report(self.completed_review())
        self.assertEqual(0, report["fully_reviewed_matched_trials"])
        candidate = next(group for group in report["groups"] if group["variant"] == "candidate")
        self.assertEqual(900, candidate["latency_ms"]["p50"])

    def test_contract_failures_are_not_treated_as_successful_quality(self):
        validation = ev.read(self.validation())
        validation["runs"][0]["attempts"][0]["valid"] = False
        validation["runs"][0]["final_valid"] = False
        path = self.save("invalid_contract.json", validation)
        report = ev.make_report(self.runs, self.exports, path, self.responses, {})
        self.assertEqual(1, sum(group["final_contract_failures"] for group in report["groups"]))

    def test_response_changes_invalidate_old_contract_validation(self):
        validation_path = self.validation()
        self.responses.write_text("{}", encoding="utf-8")
        with self.assertRaises(ValueError):
            ev.make_report(self.runs, self.exports, validation_path, self.responses, {})

    def test_run_metadata_rejects_sakura_credentials_duplicate_trials_and_negative_times(self):
        for mutate in (
            lambda run: run.update(model="Sakura-model"),
            lambda run: run.update(parameters={"api_key": "not-a-real-key"}),
            lambda run: run["attempts"][0].update(elapsed_ms=-1),
            lambda run: run["attempts"][0].update(input_tokens=True),
            lambda run: run.update(prompt_sha256="wrong-hash"),
        ):
            runs = copy.deepcopy(self.runs)
            mutate(runs[0])
            path = self.save("bad_runs.json", {"schema_version": 1, "fixture_sha256": self.fixture_hash, "runs": runs})
            with self.assertRaises(ValueError):
                ev.load_runs(path, self.exports)
        duplicate = copy.deepcopy(self.runs[0])
        duplicate["id"] = "duplicate-with-new-id"
        path = self.save("duplicate.json", {"schema_version": 1, "fixture_sha256": self.fixture_hash, "runs": self.runs + [duplicate]})
        with self.assertRaises(ValueError):
            ev.load_runs(path, self.exports)

    def test_prompt_comparison_reports_size_only_and_rejects_different_fixtures(self):
        comparison = ev.compare_prompts(*self.export_paths)
        self.assertTrue(comparison["requests"][0]["changed"])
        self.assertGreater(comparison["requests"][0]["delta"]["system_chars"], 0)
        self.assertNotIn("tokens", comparison["requests"][0]["baseline"])
        data = ev.read(self.export_paths[1])
        data["fixture_sha256"] = "different"
        with self.assertRaises(ValueError):
            ev.compare_prompts(self.export_paths[0], self.save("different.json", data))

    def test_percentiles_interpolate_and_outputs_never_overwrite(self):
        self.assertEqual({"n": 2, "p50": 200.0, "p95": 290.0}, ev.distribution([100, 300]))
        with self.assertRaises(ValueError):
            ev.write(self.responses, {})


if __name__ == "__main__":
    unittest.main()
