package com.securityhub.shared.web;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

@Getter
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final String sort;

    private PageResponse(List<T> content, int page, int size, long totalElements, int totalPages, String sort) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.sort = sort;
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), describe(page.getSort()));
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        List<T> content = page.getContent().stream().map(mapper).collect(Collectors.toList());
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), describe(page.getSort()));
    }

    private static String describe(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return null;
        }
        return sort.stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .collect(Collectors.joining(";"));
    }
}
