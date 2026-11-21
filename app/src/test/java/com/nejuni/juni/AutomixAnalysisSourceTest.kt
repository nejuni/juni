package com.nejuni.juni

import com.nejuni.juni.playback.smart.AutomixAnalysisSource
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AutomixAnalysisSourceTest {
    @Test
    fun `YouTube analysis accepts only its canonical Opus cache key`() {
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video#alt"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video#hifi"))
    }

    @Test
    fun `analysis URI asks the resolver to bypass playback substitutions`() {
        assertTrue(AutomixAnalysisSource.opusUri("video").contains("v=video&automix_opus=1"))
        assertTrue(AutomixAnalysisSource.requestsYouTubeOpus("1"))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus(null))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus("0"))
    }
}