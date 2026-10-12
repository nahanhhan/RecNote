## ADDED Requirements

### Requirement: 完整 PDF 导出

系统 SHALL 自动生成可分享的多页 PDF 并提供 PDF/Markdown/照片 ZIP。PDF SHALL 保留中文、标题、列表、表格、离线公式和相关照片；生成过程 MUST NOT 要求用户打开打印界面。导出与上传 SHALL 使用同一资料快照，失败 MUST 明确报告且不返回空白或不完整文件。导入音频 SHALL 只导出文字 PDF/Markdown。

#### Scenario: 多页图文导出

- **WHEN** 用户导出带公式、表格和本地照片的长笔记
- **THEN** 可分享完整分页 PDF 或包含 PDF/Markdown/照片的 ZIP，内容与当前笔记一致
