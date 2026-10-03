package com.ziprun.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningExtractorTest {

    @Test
    void emitsReasoningIncrementallyEvenWhenEscapesAreSplitAcrossChunks() {
        String reply = "{\"recommendations\":[{\"agent_id\":\"AGT-2\",\"confidence\":0.9,"
            + "\"reasoning\":\"Rahul has \\\"0\\\" orders\\nand caf\\u00e9 nearby.\"},{\"reasoning\":\"second\"}]}";
        ReasoningExtractor extractor = new ReasoningExtractor();

        StringBuilder streamed = new StringBuilder();
        for (char c : reply.toCharArray()) {          // worst case: one character per chunk
            streamed.append(extractor.feed(String.valueOf(c)));
        }

        assertThat(streamed.toString()).isEqualTo("Rahul has \"0\" orders\nand café nearby.");
    }

    @Test
    void nothingIsEmittedBeforeTheReasoningValueStarts() {
        ReasoningExtractor extractor = new ReasoningExtractor();

        assertThat(extractor.feed("{\"agent_id\":\"AGT-1\",\"reasoning\"")).isEmpty();
        assertThat(extractor.feed(": \"Fast")).isEqualTo("Fast");
        assertThat(extractor.feed("est\"")).isEqualTo("est");
    }

    @Test
    void resetStartsOver() {
        ReasoningExtractor extractor = new ReasoningExtractor();
        extractor.feed("{\"reasoning\":\"abc");
        extractor.reset();

        assertThat(extractor.feed("{\"reasoning\":\"xyz\"}")).isEqualTo("xyz");
    }
}
