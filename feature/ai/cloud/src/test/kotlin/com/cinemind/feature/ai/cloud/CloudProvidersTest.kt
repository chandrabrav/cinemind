package com.cinemind.feature.ai.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProvidersTest {

    @Test
    fun modelPickerKeepsOnlyTextChatModels() {
        val openai = listOf("gpt-5", "gpt-5-mini", "o4", "text-embedding-3-large", "whisper-1", "gpt-4o-realtime-preview", "dall-e-3", "tts-1")
            .filter { OpenAiCompatClient.usableChatModel(CloudProvider.OPENAI, it) }
        assertEquals(listOf("gpt-5", "gpt-5-mini", "o4"), openai)
        assertTrue(OpenAiCompatClient.usableChatModel(CloudProvider.GEMINI, "gemini-2.5-pro"))
        assertFalse(OpenAiCompatClient.usableChatModel(CloudProvider.GEMINI, "text-embedding-004"))
        assertFalse(OpenAiCompatClient.usableChatModel(CloudProvider.GEMINI, "imagen-3.0-generate"))
        assertTrue(OpenAiCompatClient.usableChatModel(CloudProvider.OPENROUTER, "anthropic/claude-sonnet"))
    }

    @Test
    fun unknownStoredProviderFallsBackToClaude() {
        assertEquals(CloudProvider.ANTHROPIC, CloudProvider.from(null))
        assertEquals(CloudProvider.GEMINI, CloudProvider.from("GEMINI"))
        assertEquals(CloudProvider.ANTHROPIC, CloudProvider.from("SOMETHING"))
    }
}
