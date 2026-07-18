package com.stockdashboard.dto;

import java.util.List;

public record PagedResult<T>(List<T> items, int pageNo, int totalCount, int totalPages) {
}
