package com.securityhub.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Client-supplied sort properties reach Spring Data as entity property paths. Anything not
 * explicitly allowed is dropped, so a malformed or probing {@code sort} parameter cannot
 * turn into a 500 or reach an unintended association.
 */
public final class PageableSupport {

    public static final int MAX_PAGE_SIZE = 100;

    private PageableSupport() {
    }

    public static Pageable sanitize(Pageable pageable, Set<String> allowedProperties, Sort fallback) {
        int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        int page = Math.max(pageable.getPageNumber(), 0);

        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (allowedProperties.contains(order.getProperty())) {
                orders.add(order);
            }
        }
        Sort sort = orders.isEmpty() ? fallback : Sort.by(orders);
        return PageRequest.of(page, size, sort);
    }
}
