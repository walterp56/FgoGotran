# Performance record

One row per run. Date: 2026-10-04. Times are average milliseconds for completed pipelines, not tap-to-display delay.

| Run / log | Change | Cases | Avg OCR | Avg translation | Avg other | Avg total | Result |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| [R1 · 02:08](<C:/Users/user/.codex/attachments/43cc2d0c-9a17-446d-b6a9-1c4517b0669c/Pasted text.txt>) | Phase 2: screenshot coordination/timing | 39 | 872 | 1098 | 179 | 2148 | No clear speed gain |
| [R2 · 02:51](<C:/Users/user/.codex/attachments/0d017beb-74f7-459c-b4ba-beb674a5bbce/Pasted text.txt>) | Phase 3: bulk pixel reuse | 20 | 881 | 919 | 124 | 1925 | Semi-auto incorrectly translated choices automatically |
| [R3 · 03:06](<C:/Users/user/.codex/attachments/7311e2ef-4fd6-4ec2-afe1-e4b2d350c02e/Pasted text.txt>) | Semi-auto behavior corrected | 21 | 1062 | 1068 | 185 | 2315 | Choice behavior restored; no proven speed gain |

OCR includes capture and preprocessing. Other includes layout, freshness checks, choice masks and rendering. Averages are rounded.

Different scenes, modes and caches mean these runs do not prove a speed improvement. Add one row for each new run.
