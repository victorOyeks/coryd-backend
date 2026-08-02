package org.coryd.common.response;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * The standard shape for every paginated endpoint in CoRyd - matches Spring Data's own default
 * Page/PageImpl Jackson output, but built explicitly instead of returning Page&lt;T&gt; directly.
 * Returning Page&lt;T&gt; raw works today but triggers a runtime warning ("Serializing PageImpl
 * instances as-is is not supported") because that shape isn't a guaranteed contract and can
 * change between Spring Data versions - this fixes the shape in place so every paginated
 * endpoint stays consistent regardless of framework upgrades.
 */
public record PagedResponse<T>(
        long totalElements,
        int totalPages,
        PageableInfo pageable,
        int size,
        List<T> content,
        int number,
        List<SortInfo> sort,
        boolean first,
        boolean last,
        int numberOfElements,
        boolean empty
) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        List<SortInfo> sortInfo = toSortInfo(page.getSort());
        PageableInfo pageableInfo = new PageableInfo(
                page.getPageable().isPaged(),
                page.getNumber(),
                page.getSize(),
                page.getPageable().getOffset(),
                sortInfo,
                page.getPageable().isUnpaged()
        );
        return new PagedResponse<>(
                page.getTotalElements(), page.getTotalPages(), pageableInfo, page.getSize(), page.getContent(),
                page.getNumber(), sortInfo, page.isFirst(), page.isLast(), page.getNumberOfElements(), page.isEmpty()
        );
    }

    private static List<SortInfo> toSortInfo(Sort sort) {
        return sort.stream()
                .map(order -> new SortInfo(
                        order.getDirection().name(),
                        order.getNullHandling().name(),
                        order.isAscending(),
                        order.getProperty(),
                        order.isIgnoreCase()
                ))
                .toList();
    }

    public record PageableInfo(boolean paged, int pageNumber, int pageSize, long offset, List<SortInfo> sort, boolean unpaged) {
    }

    public record SortInfo(String direction, String nullHandling, boolean ascending, String property, boolean ignoreCase) {
    }
}
