import contextlib
import io
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build_db
import ingest_atlas


def character(japanese, chinese, english, gender, aliases=None):
    return dict(jp_name=japanese, cn_name=chinese, en_name=english, gender=gender,
                aliases=json.dumps(aliases or [], ensure_ascii=False), category='character')


class GeneratedCharacterGenderTest(unittest.TestCase):
    def build(self, terms):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, database = root / 'source.json', root / 'fgo_terms.db'
            source.write_text(json.dumps(terms, ensure_ascii=False), encoding='utf-8')
            with patch.object(build_db, 'ROOT', root), contextlib.redirect_stdout(io.StringIO()):
                build_db.build_db(source, database)
            with contextlib.closing(sqlite3.connect(database)) as connection:
                connection.row_factory = sqlite3.Row
                self.assertEqual(3, connection.execute('pragma user_version').fetchone()[0])
                self.assertEqual('ok', connection.execute('pragma integrity_check').fetchone()[0])
                return {
                    'characters': {row['jp_name']: dict(row) for row in connection.execute('select * from character_names')},
                    'terms': {row['jp_term']: dict(row) for row in connection.execute('select * from terms')},
                }

    def test_unique_parent_supplies_male_female_and_explicit_unspecified_gender(self):
        rows = self.build([
            character('シャーロック・ホームズ', '夏洛克·福尔摩斯', 'Sherlock Holmes', '男性'),
            character('マシュ・キリエライト', '玛修·基列莱特', 'Mash Kyrielight', '女性'),
            character('未知・人物', '未知·人物', 'Unknown Person', '性別不明'),
        ])['characters']
        self.assertEqual(('福尔摩斯', 'Holmes', '男性'), tuple(rows['ホームズ'][key] for key in ('cn_name', 'en_name', 'gender')))
        self.assertEqual('女性', rows['マシュ']['gender'])
        self.assertEqual('女性', rows['キリエライト']['gender'])
        self.assertEqual('性別不明', rows['人物']['gender'])

    def test_shared_part_stays_blank_even_when_parents_have_the_same_gender(self):
        terms = [character('アルファ・共通', '甲·共通', 'Alpha Shared', '女性'),
                 character('ベータ・共通', '乙·共通', 'Beta Shared', '女性')]
        for ordered in (terms, list(reversed(terms))):
            rows = self.build(ordered)['characters']
            self.assertEqual('', rows['共通']['gender'])
            self.assertEqual('女性', rows['アルファ']['gender'])

    def test_conflicting_or_unknown_parent_gender_is_not_guessed(self):
        for second_gender in ('男性', ''):
            rows = self.build([
                character('アルファ・共通', '甲·共通', 'Alpha Shared', '女性'),
                character('ベータ・共通', '乙·共通', 'Beta Shared', second_gender),
            ])['characters']
            self.assertEqual('', rows['共通']['gender'])
        rows = self.build([character('未知・人物', '未知·人物', 'Unknown Person', '')])['characters']
        self.assertEqual('', rows['人物']['gender'])

    def test_curated_short_name_keeps_its_translation_gender_and_aliases(self):
        parent = character('シャーロック・ホームズ', '夏洛克·福尔摩斯', 'Sherlock Holmes', '男性')
        for gender in ('女性', ''):
            explicit = character('ホームズ', '自订称呼', 'Custom Holmes', gender, ['ホームス'])
            for ordered in ([parent, explicit], [explicit, parent]):
                row = self.build(ordered)['characters']['ホームズ']
                self.assertEqual(('自订称呼', 'Custom Holmes', gender), tuple(row[key] for key in ('cn_name', 'en_name', 'gender')))
                self.assertEqual(['ホームス'], json.loads(row['aliases']))

    def test_non_character_components_do_not_gain_character_gender(self):
        item = character('魔術・回路', '魔术·回路', 'Magecraft Circuit', '男性')
        item['category'] = 'game_term'
        rows = self.build([item])
        self.assertEqual({}, rows['characters'])
        self.assertEqual('', rows['terms']['回路']['gender'])

    def curated_terms(self):
        terms = []
        ingest_atlas.ingest_common_terms(terms)
        ingest_atlas.ingest_local_tsvs(terms, build_db.ROOT / 'character_names.tsv',
                                      build_db.ROOT / 'term.tsv', build_db.ROOT / 'mooncell_terms.tsv')
        return ingest_atlas.dedupe_terms(terms)

    def test_reviewed_short_rows_can_be_removed_without_changing_any_database_metadata(self):
        terms = self.curated_terms()
        reviewed = [
            character('ホームズ', '福尔摩斯', 'Holmes', '男性'),
            character('カドック', '卡多克', 'Kadoc', '男性'),
            character('ムニエル', '穆尼尔', 'Meuniere', '男性'),
            character('ゴルドルフ', '戈尔德鲁夫', 'Goredolf', '男性'),
        ]
        sources = {term['jp_name'] for term in terms}
        for short in reviewed:
            self.assertNotIn(short['jp_name'], sources)

        before = self.build(ingest_atlas.dedupe_terms(terms + reviewed))
        after = self.build(terms)
        for table in ('characters', 'terms'):
            def without_generated_ids(rows):
                return {source: {key: value for key, value in row.items() if key != 'id'}
                        for source, row in rows.items()}
            self.assertEqual(without_generated_ids(before[table]), without_generated_ids(after[table]))
        for short in reviewed:
            generated = after['characters'][short['jp_name']]
            for column in ('cn_name', 'en_name', 'gender', 'aliases'):
                self.assertEqual(short[column], generated[column], (short['jp_name'], column))

    def test_fgo_forms_and_exceptional_short_labels_remain_explicit(self):
        characters = {term['jp_name']: term for term in self.curated_terms() if term['category'] == 'character'}
        for source in ('ロムルス', 'ロムルス＝クィリヌス', 'エミヤ', 'エミヤ・オルタ',
                       'スカサハ', 'スカサハ＝スカディ', 'ダ・ヴィンチ', 'ゴッホ', 'ジャック', 'オルガマリー'):
            self.assertIn(source, characters)
        self.assertEqual('男性', characters['ロムルス']['gender'])
        self.assertEqual('性別不明', characters['ロムルス＝クィリヌス']['gender'])
        built = self.build(list(characters.values()))['characters']
        for source in ('ロムルス', 'ロムルス＝クィリヌス'):
            self.assertEqual(characters[source]['gender'], built[source]['gender'])
        self.assertIn('ダヴィンチ', json.loads(built['ダ・ヴィンチ']['aliases']))
        self.assertIn('オルガマリ', json.loads(built['オルガマリー']['aliases']))


if __name__ == '__main__':
    unittest.main()
