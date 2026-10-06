package com.ziprun.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.Function;

/**
 * Pagination for list endpoints: ?page=0&size=500 (both optional, newest first).
 * The body stays a plain JSON array, so existing clients keep working; the total
 * number of matching rows is in the X-Total-Count header.
 */
final class Paging {

    static final int DEFAULT_SIZE = 500;
    static final int MAX_SIZE = 1000;
    static final String TOTAL_HEADER = "X-Total-Count";

    private Paging() {
    }

    static Pageable of(Integer page, Integer size, Sort sort) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        return PageRequest.of(p, s, sort);
    }

    static <E, V> List<V> respond(Page<E> page, Function<E, V> view, HttpServletResponse response) {
        response.setHeader(TOTAL_HEADER, String.valueOf(page.getTotalElements()));
        return page.getContent().stream().map(view).toList();
    }
}
