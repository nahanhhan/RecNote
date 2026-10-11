package io.github.nahanhhan.lecture.core

import kotlinx.serialization.json.*
import kotlin.test.*

class NotePromptTest {
    @Test fun variablesRepeatAndUnknownVariablesStayLiteral() {
        assertEquals("  日期=系统日期 时间；标题=数学；数学；{{other}}\n", NotePrompt.render(
            "  日期={{datetime}}；标题={{title}}；{{title}}；{{other}}\n", "系统日期 时间", "数学"))
    }

    @Test fun insertedValuesAreNotExpandedOrTreatedAsReplacementSyntax() {
        val title = "会议 {{datetime}} \$1 \\路径"
        assertEquals("时间：now；标题：$title", NotePrompt.render("时间：{{datetime}}；标题：{{title}}", "now", title))
    }

    @Test fun originalScheduleNameWinsOverNumberedUserTitleAndUnboundFallsBack() {
        assertEquals("数学", NotePrompt.title("数学", "数学-1"))
        assertEquals("用户会议", NotePrompt.title("", "用户会议"))
        assertEquals("导入音频", NotePrompt.title(" ", "导入音频"))
    }

    @Test fun validationPreservesWhitespaceAndRejectsEmptyOrOversizedTemplates() {
        assertEquals("\n  提示词  \n", NotePrompt.validate("\n  提示词  \n"))
        assertFailsWith<IllegalArgumentException> { NotePrompt.validate(" \n ") }
        assertFailsWith<IllegalArgumentException> { NotePrompt.validate("a".repeat(20_001)) }
        assertFailsWith<IllegalArgumentException> { NotePrompt.render("{{title}}", "now", "") }
        assertTrue(NotePrompt.defaultTemplate.contains("{{datetime}}"))
        assertTrue(NotePrompt.defaultTemplate.contains("{{title}}"))
    }

    @Test fun snapshotsRemainExactAndLegacyJobsDoNotUseNewDefault() {
        assertEquals("已冻结 {{title}}", NotePrompt.forJob("已冻结 {{title}}"))
        assertEquals(NotePrompt.legacyInstruction, NotePrompt.forJob(""))
        assertFalse(NotePrompt.forJob("").contains("{{datetime}}"))
    }

    @Test fun customInstructionIsTheActualSystemMessageAndConfirmationPreservesIt() {
        val batch = Batch("lesson", "batch", 1, "course", emptyList(), listOf(Segment("source", 0, 1000, "资料")), emptyList())
        val definition = buildJsonObject { put("type", "function"); put("function", buildJsonObject { put("name", "save_class_notes") }) }
        val prompt = "按主题整理，突出重点，可以解释术语。"
        val request = ChatProtocol.initial("model", batch, emptyList(), definition, false, prompt)
        assertEquals(prompt, request.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content)
        val followup = ChatProtocol.followup(request, buildJsonObject { put("role", "assistant") }, ChatProtocol.receipt("id", true, "batch"))
        assertEquals(request.getValue("messages").jsonArray.first(), followup.getValue("messages").jsonArray.first())
        assertEquals("save_class_notes", request.getValue("tool_choice").jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun debugLoggingDoesNotChangeRequestOrExposeSystemAndDeveloperText() {
        val body = buildJsonObject { put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", "fake-private-template") })
            add(buildJsonObject { put("role", "developer"); put("content", "fake-private-context") })
            add(buildJsonObject { put("role", "user"); put("content", "合成资料") })
        }) }
        val logged = CloudRequests.loggable(body)
        assertFalse(logged.toString().contains("fake-private"))
        assertTrue(logged.toString().contains("合成资料"))
        assertTrue(body.toString().contains("fake-private-template"))
    }
}
