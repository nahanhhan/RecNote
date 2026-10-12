# 本地识别模型

FireRedASR2 AED/CTC 保留原有下载源。新增两种较小模型；当前仅使用已经核实的 GitHub 官方归档，选择魔塔源时界面提示切换。大小为归档实际长度；安装只提取必需的 INT8 模型和词表，不保留浮点模型或示例音频。

| 模型 | 语言 | 下载 | 安装必需文件 | SHA256 |
| --- | --- | --- | --- | --- |
| SenseVoice Small INT8 | 中文、英文、日语、韩语、粤语 | 163002883 B | model.int8.onnx、tokens.txt | 7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e |
| Whisper Tiny INT8 | 多语言自动识别 | 116204861 B | tiny-encoder.int8.onnx、tiny-decoder.int8.onnx、tiny-tokens.txt | c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1 |

2026-10-12 已完整下载两份官方归档，核对长度、SHA256、tar 文件名和 INT8 文件大小。SenseVoice 哈希与 GitHub 发布资产 digest 一致；Whisper 旧发布资产无 digest，记录完整下载计算值。识别质量/速度仍需手机验收。

官方来源：[SenseVoice 说明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)、[Whisper 归档说明](https://k2-fsa.github.io/sherpa/onnx/spoken-language-identification/pretrained_models.html)、[官方模型发布](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)。
