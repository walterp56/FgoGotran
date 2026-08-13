# Voice Tune Progress

status: RUNNING
source: C:\mywork\FgoGotran\term_builder\jp_cn_name_map.tsv
approved_target: C:\mywork\FgoGotran\term_builder\voice_tune\character_voice_profiles_cn.tsv
review_target: C:\mywork\FgoGotran\term_builder\voice_tune\needs_review.tsv

batch_size: 20
processed_speaker_groups: 560
last_speaker_id: アントニオ
approved_profiles_count: 488
needs_review_count: 0
last_updated: 2026-08-12 19:56:17 +08:00

## Last Batch

- operation: approved focused rebuild for ペペロンチーノ voice
- profile rows inserted: 0
- profile rows updated: 1
- rows with actual value changes: 1
- needs_review rows added: 0
- ペペロンチーノ: androgynous / zh-CN-Yunxia:DragonHDFlashLatestNeural / cheerful / +2% / 1.08 -> mature_male / zh-CN-YunyeNeural / cheerful / +8% / 1.02
- result: tuned toward mature male high-pitch 男大姐 feel after Azure test.

- operation: approved focused rebuild to separate カドック, ペペロンチーノ, and ムニエル voices
- profile rows inserted: 0
- profile rows updated: 3
- rows with actual value changes: 2
- needs_review rows added: 0
- カドック: keep zh-CN-Yunxi:DragonHDFlashLatestNeural / chat / 0% / 1.07
- ペペロンチーノ: zh-CN-Yunxi:DragonHDFlashLatestNeural / chat / +1% / 1.07 -> zh-CN-Yunxia:DragonHDFlashLatestNeural / cheerful / +2% / 1.08
- ムニエル: zh-CN-Yunxi:DragonHDFlashLatestNeural / chat / +1% / 1.06 -> zh-CN-Yunhan:DragonHDFlashLatestNeural / cheerful / 0% / 1.06
- result: the three rows now use distinct exact voice models.

## Notes

- Manual review workflow: propose first, write only after approval.
- Approved profiles are tuned for faster, more dialogue-like delivery to reduce 人機感.
