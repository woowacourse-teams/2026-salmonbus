package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.dto.BoardResponse;
import com.gustler.backend.api.route.RouteId;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes/{routeId}/board")
public class BoardController {

    private final BoardQueryService boardQueryService;

    public BoardController(final BoardQueryService boardQueryService) {
        this.boardQueryService = boardQueryService;
    }

    @GetMapping
    public ResponseEntity<BoardResponse> getBoard(
        @PathVariable final String routeId
    ) {
        final BoardOverview overview = boardQueryService.getBoard(new RouteId(routeId));

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(overview.cacheMaxAge()).cachePublic())
            .body(BoardResponse.from(overview.board()));
    }
}
