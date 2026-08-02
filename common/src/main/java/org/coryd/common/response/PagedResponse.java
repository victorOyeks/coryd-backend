package org.coryd.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/** The standard shape for every paginated endpoint in CoRyd - matches marketrun-ng's PagedResponse convention. */
public record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }
}
