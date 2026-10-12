package io.github.nahanhhan.lecturerecording.models

data class ModelSpec(val id: String, val label: String, val archive: String, val bytes: Long,
    val sha256: String, val files: Set<String>,
    val modelscope: String, val modelscopeBranch: String = "master", val description: String = "") {
    val url get() = urlFor(DownloadSource.GITHUB)

    /** 按下载源返回同一归档的直链：GitHub Releases 或魔塔社区镜像。 */
    fun urlFor(source: DownloadSource) = when (source) {
        DownloadSource.GITHUB -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive"
        DownloadSource.MODELSCOPE -> {
            require(modelscope.isNotBlank()) { "此模型暂无已验证的魔塔镜像，请选择 GitHub 下载源" }
            "https://modelscope.cn/models/$modelscope/resolve/$modelscopeBranch/$archive"
        }
    }
}

object ModelCatalog {
    val models = listOf(
        ModelSpec("aed", "FireRedASR2 AED · 默认", "sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26.tar.bz2",
            838589068, "43015b3f1643a5688b4821e8ed323473d38b798c4ec291471fe00df1bcfc4f1c",
            setOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
            "adaada88/sherpa-onnx-fire-red-asr2-aed-zh-en-int8", description = "中文、英文 · 较大的课堂识别模型，优先准确度"),
        ModelSpec("ctc", "FireRedASR2 CTC · 备用", "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2",
            520516278, "1da8b737ecc5e29f36759a4460c754863e7c919a4ba325aea187331fbfc83274",
            setOf("model.int8.onnx", "tokens.txt"),
            "adaada88/sherpa-onnx-fire-red-asr2-ctc-zh-en-int8", description = "中文、英文 · CTC 方案，体积小于默认模型"),
        ModelSpec("sensevoice", "SenseVoice Small INT8 · 轻量多语言",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            163002883, "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
            setOf("model.int8.onnx", "tokens.txt"), "",
            description = "中文、英文、日语、韩语、粤语 · 下载约 155 MB，安装约 229 MB；适合节省空间。GitHub 下载"),
        ModelSpec("whisper_tiny", "Whisper Tiny INT8 · 多语言",
            "sherpa-onnx-whisper-tiny.tar.bz2", 116204861,
            "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
            setOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt"), "",
            description = "多语言自动识别 · 下载约 111 MB，安装约 99 MB；中文复杂课堂建议优先使用其他模型。GitHub 下载")
    )
    fun get(id: String) = models.first { it.id == id }
}
