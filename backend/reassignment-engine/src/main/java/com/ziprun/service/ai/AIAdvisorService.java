package com.ziprun.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.ReasoningListener;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.gateway.LLMException;
import com.ziprun.routing.gateway.LLMGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Advisory Service: "what does the AI say?"
 *
 * Responsibilities:
 * - Pick the prompt for the situation (initial assignment vs recovery re-plan)
 * - Call the LLM gateway (which handles provider fallback and timeouts)
 * - Parse the reply into AIRecommendationOptions
 *
 * Failures are thrown as typed LLMExceptions, never swallowed into null, so the
 * caller (AIRoutingStrategy) can log the exact failure mode and fall back.
 * Whether the recommended agent actually exists is checked by the strategy,
 * which owns the roster it passed in.
 */
@Service
public class AIAdvisorService {
    private static final Logger log = LoggerFactory.getLogger(AIAdvisorService.class);

    private final LLMGateway llmGateway;
    private final ObjectMapper objectMapper;

    public AIAdvisorService(LLMGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws LLMException if every provider fails or the reply can't be parsed
     */
    public AIRecommendation advise(Order order, List<Agent> availableAgents, RoutingContext context) {
        String prompt = context.isRecovery()
            ? PromptBuilder.buildReplanPrompt(order, availableAgents, context)
            : PromptBuilder.buildInitialAssignmentPrompt(order, availableAgents, context);
        log.debug("{} prompt for order {}:\n{}", context.isRecovery() ? "Re-plan" : "Initial", order.getId(), prompt);

        LLMGateway.LLMReply reply = context.isStreaming()
            ? streamReasoning(prompt, context.listener())
            : llmGateway.callLLM(prompt);
        List<AIRecommendationOption> options = parse(reply.text());
        log.debug("LLM ({}) returned {} option(s) for order {}", reply.provider(), options.size(), order.getId());
        return new AIRecommendation(reply.provider(), options);
    }

    /**
     * Streams the reply, forwarding only the top recommendation's reasoning text
     * to the listener as it arrives. The full reply is still parsed and
     * validated afterwards exactly like the non-streaming path.
     */
    private LLMGateway.LLMReply streamReasoning(String prompt, ReasoningListener listener) {
        ReasoningExtractor extractor = new ReasoningExtractor();
        return llmGateway.streamLLM(prompt, new LLMGateway.StreamSink() {
            @Override
            public void chunk(String text) {
                String delta = extractor.feed(text);
                if (!delta.isEmpty()) {
                    listener.token(delta);
                }
            }

            @Override
            public void providerFailed(String provider, LLMException failure) {
                extractor.reset();
                listener.restart(provider + " failed (" + failure.getKind() + "), trying next provider");
            }
        });
    }

    /**
     * Accepts either {"recommendations":[{...},...]} (what we ask for) or a single
     * {"agent_id"|"agentId", "confidence", "reasoning"} object (camelCase also accepted),
     * optionally wrapped in markdown fences or surrounded by prose.
     */
    List<AIRecommendationOption> parse(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(raw));
        } catch (Exception e) {
            throw new LLMException(LLMException.Kind.UNPARSEABLE, "LLM reply is not JSON: " + abbreviate(raw), e);
        }

        List<JsonNode> items = new ArrayList<>();
        if (root.has("recommendations") && root.get("recommendations").isArray()) {
            root.get("recommendations").forEach(items::add);
        } else {
            items.add(root);
        }

        List<AIRecommendationOption> options = new ArrayList<>();
        for (JsonNode item : items) {
            String agentId = text(item, "agent_id", "agentId");
            JsonNode confidence = item.get("confidence");
            options.add(new AIRecommendationOption(
                agentId,
                confidence != null && confidence.isNumber() ? confidence.asDouble() : null,
                text(item, "reasoning")
            ));
        }
        if (options.stream().allMatch(o -> o.agentId() == null)) {
            throw new LLMException(LLMException.Kind.UNPARSEABLE, "LLM JSON has no agent_id: " + abbreviate(raw));
        }
        return options;
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object found");
        }
        return raw.substring(start, end + 1);
    }

    private static String text(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
