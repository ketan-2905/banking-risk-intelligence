package com.example.bankingrisk.analyst.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
    @NotBlank(message = "reason must not be blank")
    @Size(max = 500, message = "reason must not exceed 500 characters")
    String reason
) {}
