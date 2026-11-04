# Protocol v1 云盘 JSON 样例与字段说明

本目录存放 Voice2Note (`v2n rec-daemon`) 与 RecNote 共同遵守的四份云盘 JSON 样例（编码均为 UTF-8 无 BOM，路径基准统一相对于云盘根）：

1. `manifest.json`（手机写入，滚动清单）
   - 顶层字段：`protocol_version`, `session_id`, `revision`, `sample_rate`, `channels`, `encoding`, `chunks`
   - `chunks[]` 字段：`sequence`, `path`, `start_sample`, `sample_count`, `sha256`（不含 `bytes`）
2. `final.json`（手机写入，停止后的最终清单）
   - 顶层字段：`protocol_version`, `session_id`, `revision`, `sample_rate`, `channels`, `encoding`, `total_samples`, `chunk_count`, `chunks`
   - `chunks[]` 字段：与 `manifest.json` 相同
3. `index.json`（电脑写入，`.transcripts/index.json` 结果索引）
   - 顶层字段：`protocol_version`, `session_id`, `revision`, `chunks`
   - `chunks[]` 字段：`sequence`, `audio_sha256`, `status`（`completed` 或 `failed`）, `path`, `sha256`, `error`
4. `complete.json`（电脑写入，`.transcripts/complete.json` 完成标记）
   - 顶层字段：`protocol_version`, `session_id`, `final_manifest_sha256`, `chunks`
   - `chunks[]` 字段：`sequence`, `audio_sha256`, `path`, `sha256`
