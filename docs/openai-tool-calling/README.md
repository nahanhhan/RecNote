# OpenAI API v1 Tool Calling 接口约定

更新日期：2026-10-02。本文是接口约定与离线格式示例；安卓适配器及本地核心校验已实现，尚未调用真实云端服务。

## 1. 接口边界

首版沿用自定义服务地址、模型名称、API Key，选择 OpenAI-compatible Chat Completions 协议。`v1` 是接口路径的一部分，不是一个独立的“工具调用版本号”。本约定对应 `/v1/chat/completions`，或者其他服务商以 `/v1` 或 `compatible-mode/v1` 结尾的基础地址加 `/chat/completions`。

HTTP 使用 `POST`、`Content-Type: application/json` 和 `Authorization: Bearer <USER_API_KEY>`。规范字段采用 Chat Completions 的 `tools[].function`、`assistant.tool_calls`、`role: tool` 和 `tool_call_id`。[官方 Chat Completions 参考](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

本地录音、sherpa-onnx 转写、照片时间绑定继续在手机内完成。用户录完并手动点击“整理笔记”后，App 才向云端发送转写文字和选定照片。照片原图、录音文件、数据库路径和 API Key 不作为模型资料。没有二次识别环节。

录音期间产生的段落和照片作为本地对象保存；它们在整理时进入 `role: user` 的资料输入。只有真实模型返回的工具调用使用 `role: assistant` + `tool_calls`，App 的实际执行结果才能使用 `role: tool`。

照片命名使用 `ast_<录音相对时间毫秒，至少九位>_<课堂内拍摄序号，至少三位>.jpg`，例如 `ast_000075123_001.jpg` 对应录音 75.123 秒的第 1 张照片。`ast_` 为固定前缀，录音位置使用采集音频的相对时间；实际拍摄日期另存 Unix 毫秒时间，不用 AST 时区或系统墙钟推算录音位置。

云端输入必须在每张图片旁附文字标签，显式传入照片编号、文件名、`audio_time_ms` 和可读时间。`image_url` 中的 URL 或 Base64 并不会代替原始文件名元数据。模型结合照片标签与带时间的转写段落选取照片；工具返回只提交 `photo_ids`，App 据本地记录插图和回听。

## 2. 工具定义

首版只向云端暴露一个函数：`save_class_notes`。模型提交标题、分节 Markdown、来源段落编号和照片编号，App 校验后保存当前批次。当前课堂、整理任务、批次和来源版本由 App 绑定，模型没有选择其他课堂或覆盖原始转写的权限。

完整定义见 `tool-definition.json`：

- `type` 为 `function`，定义嵌套在 `function` 内。
- 默认 `strict: true`；每层对象设置 `additionalProperties: false`，全部字段列入 `required`；没有照片时返回空数组。
- 指定 `tool_choice` 调用 `save_class_notes`，并设置 `parallel_tool_calls: false`、`stream: false`。[官方 Function Calling 指南](https://developers.openai.com/api/docs/guides/function-calling)

严格模式用于约束参数结构，不能保证内容事实准确；App 仍校验来源、工具名称和数据关联。

## 3. 请求、执行与回传

按以下文件顺序读取一个完整示例：

1. `examples/01-request.json`：文字和图片输入、工具定义、指定工具选择。
2. `examples/02-response.json`：模型返回 `choices[0].message.tool_calls`；`function.arguments` 是 JSON 字符串。
3. App 将参数字符串解析为 JSON，并执行下方校验和本地事务。
4. `examples/03-tool-result.json`：App 生成工具执行结果，`content` 是字符串化 JSON，`tool_call_id` 对应模型返回的调用编号。
5. `examples/04-followup-request.json`：保留此前消息，追加原始 assistant 工具调用消息和对应 tool 结果，再以 `tool_choice: none` 请求完成确认。
6. `examples/05-final-response.json`：普通文字仅确认执行结果，笔记正文仍以已校验的工具参数为准。

工具回传必须准确反映 App 执行结果。保存失败时返回 `ok: false` 和错误说明，不得先生成成功回执。模型不会直接执行手机上的保存操作。[官方调用流程](https://developers.openai.com/api/docs/guides/function-calling)

示例中的模型名为配置占位符，接口地址和 Key 位于实际 HTTP 配置中。图片为无内容的 1×1 测试图，仅用于演示多模态字段格式；实际课堂应替换为用户选择的照片。响应是手写的协议样例，不是线上模型测试结果。

## 4. App 执行规则

- 只接受 `save_class_notes`，并要求当前整理请求返回一个完整调用；普通正文、未知工具、未完成参数和拒绝回答不进入笔记数据库。
- 在解析成功后按工具的 JSON Schema 校验，再校验所有来源编号属于当前批次及当前来源版本。每节至少有转写来源或照片来源；未知编号和空白正文返回错误。
- 使用 App 的 `job_id + batch_id + source_revision` 做幂等保存，事务完成后才回传成功。同批次重复调用返回既有结果，不重复追加。
- 分批整理遵循既定上限：最多六张照片或六千字符。每批工具回合独立，完成后由 App 按批次顺序合并；不通过工具调用重新识别音频。
- 模型仅返回照片编号。App 使用本地图片和已保存的录音时间展示、回听及导出，避免模型生成错误时间或路径。
- 工具调用消息和执行回执作为整理任务记录保存；它们与录音原稿、照片记录分别保存。
- 用户启动整理后，执行工作由后台前台服务持有；锁屏或页面退出不销毁任务。工具调用编号、参数和保存回执持久化，恢复后复用已完成批次，不重复保存；系统限制或主动停止时保存进度并显示可恢复状态。详见主方案“后台、锁屏与异常恢复”。
- 网络失败、错误 Key、限流、格式不兼容和错误引用均保留本地材料。自动修正不绕过 Schema 和来源校验，用户可以手动重试。

## 5. 兼容性与设置

云端模型必须同时支持图片输入以及本约定的 Chat Completions Function Calling。设置页的测试覆盖文字、图片、指定工具调用及工具结果回传；成功后才标记接口可用。

`strictMode` 默认开启。用户可以为不支持严格模式的兼容服务关闭该设置，请求中的 `function.strict` 随之变为 `false`；App 的本地 Schema、来源及幂等校验始终执行。服务连基础 Tool Calling 都不支持时显示不兼容，不把普通 JSON 正文冒充工具调用。

首版仅接入此 Chat Completions 适配器。Responses API 同样可以位于 `/v1` 下，但其工具与回传字段形状不同，不混用到本协议；特定模型能否在所选端点调用工具，应以其服务实际能力为准。

## 6. 验证边界

当前已校验 JSON 示例、嵌套 Schema、参数字符串、工具名称、来源编号和调用编号对应关系，并加入核心自动测试；未配置 API Key、未发送课堂资料、未验证真实服务。安卓构建与运行验证以 CI 结果为准，验证记录随 change 工件归档。

应用实现时补充：未知工具、错误类型、额外字段、跨批次来源、重复保存、超时后重试、错误回执、工具能力不支持、多图及真实 API 往返测试。

图片关联额外验证：`ast_` 文件名解析出的相对时间与 `audio_time_ms` 一致，图片标签与 `input_image_index` 一致，所有 `photo_ids` 均能解析到当前批次图片；原图和导出副本的文件名一致。后台额外验证：在保存工具结果之前或之后锁屏、重建页面、断网或中断任务，恢复后调用编号正确、原始资料完整且保存幂等。
