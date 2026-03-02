package com.fun.ai.claw.api.model;

import java.util.List;

public record ListResponse<T>(
        List<T> items
) {
}

