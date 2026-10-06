package com.ziprun.controller;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.exception.NotFoundException;
import com.ziprun.service.suggestion.SuggestionService;
import com.ziprun.controller.dto.SuggestionView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
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
    public List<SuggestionView> listSuggestions(@RequestParam(name = "status", required = false) String status,
                                @RequestParam(name = "page", required = false) Integer page,
                                @RequestParam(name = "size", required = false) Integer size,
                                HttpServletResponse response) {
        SuggestionStatus filter = status == null || status.isBlank() ? null : EnumParam.parse(SuggestionStatus.class, status, "suggestion status");
        return Paging.respond(suggestionService.list(filter, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
            SuggestionView::from, response);
    }

    @GetMapping("/{id}")
    public SuggestionView getSuggestion(@PathVariable String id) {
        return SuggestionView.from(suggestionService.findById(id).orElseThrow(() -> new NotFoundException("Suggestion", id)));
    }

    @PatchMapping("/{id}")
    public SuggestionView updateSuggestion(@PathVariable String id, @Valid @RequestBody UpdateSuggestionRequest request) {
        return SuggestionView.from(suggestionService.updateStatus(id, EnumParam.parse(SuggestionStatus.class, request.status(), "suggestion status")));
    }

    public record UpdateSuggestionRequest(@NotBlank String status) {
    }
}
