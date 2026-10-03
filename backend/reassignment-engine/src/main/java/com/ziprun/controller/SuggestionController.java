package com.ziprun.controller;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.exception.NotFoundException;
import com.ziprun.service.suggestion.SuggestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Suggestion Controller: REST API for reassignment suggestions.
 *
 * Endpoints:
 * GET    /suggestions?status=      - List suggestions (optionally filtered by status)
 * GET    /suggestions/{id}         - Get single suggestion
 * PATCH  /suggestions/{id}         - Accept or reject: { "status": "ACCEPTED" | "REJECTED" }
 *
 * PATCH is the human checkpoint of the agentic loop. Accepting reassigns the
 * order in the same transaction (see SuggestionService.updateStatus).
 */
@RestController
@RequestMapping("/suggestions")
public class SuggestionController {

    private final SuggestionService suggestionService;

    public SuggestionController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping
    public List<ReassignmentSuggestion> listSuggestions(@RequestParam(name = "status", required = false) String status) {
        if (status == null || status.isBlank()) {
            return suggestionService.findAll();
        }
        return suggestionService.findByStatus(EnumParam.parse(SuggestionStatus.class, status, "suggestion status"));
    }

    @GetMapping("/{id}")
    public ReassignmentSuggestion getSuggestion(@PathVariable String id) {
        return suggestionService.findById(id).orElseThrow(() -> new NotFoundException("Suggestion", id));
    }

    @PatchMapping("/{id}")
    public ReassignmentSuggestion updateSuggestion(@PathVariable String id, @Valid @RequestBody UpdateSuggestionRequest request) {
        return suggestionService.updateStatus(id, EnumParam.parse(SuggestionStatus.class, request.status(), "suggestion status"));
    }

    public record UpdateSuggestionRequest(@NotBlank String status) {
    }
}
