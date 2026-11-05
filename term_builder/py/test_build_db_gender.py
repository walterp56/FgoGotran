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

    def test_english_short_names_and_formal_names_keep_their_own_identity_and_gender(self):
        terms = self.curated_terms()
        sources = {term['jp_name'] for term in terms}
        self.assertNotIn('草十郎', sources)
        rows = self.build(terms)['characters']
        self.assertEqual(('Sizuki Soujyuro', '男性'),
                         (rows['静希草十郎']['en_name'], rows['静希草十郎']['gender']))
        self.assertEqual(('Soujyuro', '男性'),
                         (rows['草十郎']['en_name'], rows['草十郎']['gender']))
        for short, formal, short_target, formal_target in (
            ('エルン', 'エルルーン', 'Rún', 'Ölrún'),
            ('イルス', 'ゲイルスケグル', 'Irs', 'Geirskögul'),
        ):
            self.assertEqual(short_target, rows[short]['en_name'])
            self.assertEqual(formal_target, rows[formal]['en_name'])
            self.assertNotEqual(rows[short]['en_name'], rows[formal]['en_name'])
            self.assertEqual('女性', rows[short]['gender'])
            self.assertEqual('女性', rows[formal]['gender'])
            self.assertNotIn(formal, json.loads(rows[short]['aliases']))

    def test_localized_nemo_titles_do_not_change_generic_component_meanings(self):
        rows = self.build(self.curated_terms())['characters']
        for full, component, full_target, component_target, chinese in (
            ('ネモ・エンジン', 'エンジン', 'Nemo Engineer', 'Engine', '尼莫·引擎'),
            ('ネモ・ベーカリー', 'ベーカリー', 'Nemo Baker', 'Bakery', '尼莫·烘焙坊'),
        ):
            self.assertEqual(full_target, rows[full]['en_name'])
            self.assertEqual(component_target, rows[component]['en_name'])
            self.assertEqual(chinese, rows[full]['cn_name'])
            self.assertEqual('女性', rows[full]['gender'])
            self.assertEqual('女性', rows[component]['gender'])

    def test_full_vessel_names_preserve_existing_short_labels_and_gender(self):
        terms = self.curated_terms()
        sources = {term['jp_name'] for term in terms}
        rows = self.build(terms)['characters']
        for full, short, chinese, english in (
            ('バゼット・フラガ・マクレミッツ', 'バゼット', '巴泽特', 'Bazett'),
            ('カレン・Ｃ・オルテンシア', 'カレン', '卡莲', 'Caren'),
        ):
            self.assertIn(full, sources)
            self.assertNotIn(short, sources)
            self.assertEqual((chinese, english, '女性', '[]'),
                             tuple(rows[short][key] for key in ('cn_name', 'en_name', 'gender', 'aliases')))
        # A family name shared by two people no longer identifies one parent.
        self.assertEqual('Edelfelt', rows['エーデルフェルト']['en_name'])
        self.assertEqual('', rows['エーデルフェルト']['gender'])

    def test_gogh_aliases_and_clytie_labels_do_not_collapse_into_the_historical_painter(self):
        rows = self.build(self.curated_terms())['characters']
        self.assertNotIn('ヴァン・ゴッホ', rows)
        for source, chinese, english, gender in (
            ('ゴッホ', '梵高', 'Van Gogh', '女性'),
            ('クリュティエ', '克吕提厄', 'Clytie', '女性'),
            ('クリュティエ＝ヴァン・ゴッホ', '克吕提厄·梵高', 'Clytie van Gogh', '女性'),
            ('ヴィンセント・ヴァン・ゴッホ', '文森特·梵高', 'Vincent van Gogh', '男性'),
        ):
            self.assertEqual((chinese, english, gender),
                             tuple(rows[source][key] for key in ('cn_name', 'en_name', 'gender')))
        self.assertCountEqual(['ヴァン・ゴッホ', 'ヴァンゴッホ'], json.loads(rows['ゴッホ']['aliases']))
        self.assertNotIn('クリュティエ', json.loads(rows['ゴッホ']['aliases']))
        self.assertNotIn('ヴィンセント・ヴァン・ゴッホ', json.loads(rows['ゴッホ']['aliases']))

    def test_gogh_full_names_do_not_overwrite_von_or_lose_clyties_chinese_short_name(self):
        rows = self.build(self.curated_terms())['characters']
        self.assertEqual('von', rows['ヴァン']['en_name'])
        self.assertEqual('男性', rows['ヴィンセント']['gender'])
        self.assertEqual(('克吕提厄', 'Clytie', '女性'),
                         tuple(rows['クリュティエ'][key] for key in ('cn_name', 'en_name', 'gender')))
        self.assertEqual('Paracelsus von Hohenheim', rows['ヴァン・ホーエンハイム・パラケルスス']['en_name'])

    def test_illya_and_emiya_identities_do_not_collapse_to_other_full_names(self):
        rows = self.build(self.curated_terms())['characters']
        self.assertEqual(('伊莉雅', 'Illya', '女性'),
                         tuple(rows['イリヤ'][key] for key in ('cn_name', 'en_name', 'gender')))
        self.assertEqual('Illyasviel von Einzbern', rows['イリヤスフィール・フォン・アインツベルン']['en_name'])
        self.assertNotIn('イリヤ', json.loads(rows['イリヤスフィール・フォン・アインツベルン']['aliases']))
        for source, english in (('エミヤ', 'Emiya'), ('衛宮士郎', 'Shirou Emiya'),
                                ('衛宮切嗣', 'Kiritsugu Emiya')):
            self.assertEqual((english, '男性'), (rows[source]['en_name'], rows[source]['gender']))
        for ambiguous_single_character in ('凛', '桜', 'Ｃ', 'C'):
            self.assertNotIn(ambiguous_single_character, rows)


if __name__ == '__main__':
    unittest.main()
