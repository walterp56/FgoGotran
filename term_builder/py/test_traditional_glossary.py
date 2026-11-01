import json
import tempfile
import unittest
from pathlib import Path

from check_traditional_glossary import CurrentEntry, audit, load_candidates, load_current_entries, read_overrides, source_key


class TraditionalGlossaryTest(unittest.TestCase):
    def test_three_columns_and_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "overrides.tsv"
            path.write_text("jp_source\tcn_reference\tzh_hant\nシオン\t希翁\t紫苑\n", encoding="utf-8")
            self.assertEqual("紫苑", read_overrides(path)[source_key("シオン")]["zh_hant"])
            with path.open("a", encoding="utf-8") as stream:
                stream.write("シオン？\t希翁\t紫苑\n")
            with self.assertRaises(ValueError):
                read_overrides(path)

    def test_stale_missing_and_alias_inheritance(self):
        rule = {"jp_source": "シオン", "cn_reference": "希翁", "zh_hant": "紫苑"}
        current = {"シオン": [CurrentEntry("シオン", "希翁", "シオン")],
                   "別名": [CurrentEntry("別名", "希翁", "シオン")]}
        report, errors = audit({"シオン": rule}, current, {"別名": {"紫苑"}}, {})
        self.assertEqual([], errors)
        self.assertEqual(1, len(report))
        _, errors = audit({"シオン": rule}, {}, {}, {})
        self.assertEqual(["missing_source: シオン"], errors)
        current["シオン"] = [CurrentEntry("シオン", "新譯名", "シオン")]
        _, errors = audit({"シオン": rule}, current, {}, {})
        self.assertEqual(["stale_reference: シオン"], errors)

    def test_rosters_are_aligned_by_id_not_list_position(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            (cache / "x__JP__nice_servant.json").write_text(json.dumps([
                {"id": 1, "name": "シオン"}, {"id": 2, "name": "オベロン"}]), encoding="utf-8")
            (cache / "x__TW__nice_servant.json").write_text(json.dumps([
                {"id": 2, "name": "奧伯隆"}, {"id": 1, "name": "紫苑"}]), encoding="utf-8")
            result = load_candidates(cache, cache / "absent.tsv")
            self.assertEqual({"紫苑"}, result["シオン"])
            self.assertEqual({"奧伯隆"}, result["オベロン"])

    def test_conflicting_candidates_are_never_approved(self):
        current = {"シオン": [CurrentEntry("シオン", "希翁", "シオン")]}
        report, errors = audit({}, current, {"シオン": {"紫苑", "另一人"}}, {})
        self.assertEqual("reference_conflict", report[0]["status"])
        self.assertEqual([], errors)

    def test_term_only_override_and_alias_are_checked_without_a_kind_column(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            characters, terms, overrides = (root / name for name in ('characters.tsv', 'terms.tsv', 'overrides.tsv'))
            characters.write_text('jp_name\tcn_name\taliases\nシオン\t希翁\t\n', encoding='utf-8')
            terms.write_text('jp_term\tcn_term\taliases\nルールブレイカー\t万符必应破戒\t破戒すべき全ての符\n', encoding='utf-8')
            overrides.write_text('jp_source\tcn_reference\tzh_hant\nルールブレイカー\t万符必应破戒\t萬符必行破戒\n', encoding='utf-8')
            current = load_current_entries(characters, terms)
            candidates = {source_key('破戒すべき全ての符'): {'萬符必行破戒'}}
            report, errors = audit(read_overrides(overrides), current, candidates, {})
            self.assertEqual([], errors)
            self.assertEqual(['covered'], [row['status'] for row in report])

    def test_skill_and_np_candidates_match_nested_ids_and_keep_conflicts_for_review(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            jp = [{'id': 1, 'name': 'シオン', 'skills': [{'id': 2, 'name': '魔術'}, {'id': 1, 'name': '結界'}],
                   'noblePhantasms': [{'id': 3, 'name': '破戒すべき全ての符'}]}]
            tw = [{'id': 1, 'name': '紫苑', 'skills': [{'id': 1, 'name': '結界'}, {'id': 2, 'name': '魔術'},
                    {'id': 2, 'name': '另一名稱'}], 'noblePhantasms': [{'id': 4, 'name': '不相關寶具'},
                    {'id': 3, 'name': '萬符必行破戒'}]}]
            for region, rows in [('JP', jp), ('TW', tw)]:
                (cache / f'x__{region}__nice_servant.json').write_text(json.dumps(rows), encoding='utf-8')
            candidates = load_candidates(cache, cache / 'absent.tsv')
            self.assertEqual({'萬符必行破戒'}, candidates[source_key('破戒すべき全ての符')])
            self.assertEqual({'魔術', '另一名稱'}, candidates[source_key('魔術')])
            self.assertEqual({'結界'}, candidates[source_key('結界')])


if __name__ == "__main__":
    unittest.main()
