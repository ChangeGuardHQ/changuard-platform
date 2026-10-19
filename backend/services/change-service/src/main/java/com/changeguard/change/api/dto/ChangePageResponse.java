package com.changeguard.change.api.dto;

import java.util.List;

/** Stable API pagination shape independent of Spring Data's internal Page serialization. */
public record ChangePageResponse(List<ChangeResponse> items, int page, int size, long totalElements, int totalPages) {
}
