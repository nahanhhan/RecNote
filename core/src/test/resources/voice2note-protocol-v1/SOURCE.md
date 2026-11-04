# 对端原样例来源

- 仓库：https://github.com/zhangtony239/Voice2Note.git
- 提交：`7dd3e1d3c88fdf25e67954a91ac7e3eedcfc206a`
- 目录：`tests/fixtures/protocol_v1/`
- `manifest.json`、`final.json`、`index.json`、`complete.json` 及 `README.md` 均按 Git blob 原字节复制，使用 `.gitattributes` 禁止换行转换；Kotlin 测试同时核对文件摘要和字段集合。

四份 JSON 用于字段、序列化和块引用断言；其中内容摘要是示例值，没有对应的 WAV／原稿实体，不作为真实摘要链验收。实际文件摘要与完成条件另由合成数据测试验证，不修改对端原件或放松生产校验。

探测回执字段另对照同提交 `src/voice2note/rec_daemon/storage.py` 的 `receipt_payload`：`protocol_version, probe_path, probe_sha256, asr_ready, timestamp`，时间格式为 UTC `YYYY-MM-DDTHH:mm:ssZ`。电脑模板 `MAX_AUDIO_CHUNK_BYTES=16777216`（16 MiB），实际部署可配置；RecNote 保留自身短块和读取上限。
