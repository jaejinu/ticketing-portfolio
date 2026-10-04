package com.ticketing.auth.api.dto;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID userId, String email, String name, List<String> roles) {
}
