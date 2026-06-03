package com.example.bankingrisk.analyst;

import com.example.bankingrisk.analyst.dto.AlertDetailResponse;
import com.example.bankingrisk.analyst.dto.AlertSummaryResponse;
import com.example.bankingrisk.analyst.dto.ReviewDecisionRequest;
import com.example.bankingrisk.analyst.dto.ReviewDecisionResponse;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/analyst/alerts")
public class AnalystController {

    private final AnalystService analystService;

    public AnalystController(AnalystService analystService) {
        this.analystService = analystService;
    }

    @GetMapping
    public ResponseEntity<List<AlertSummaryResponse>> listAlerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(defaultValue = "50") int limit) {

        RiskAlertStatus statusEnum = status != null ? RiskAlertStatus.valueOf(status) : null;
        RiskLevel riskLevelEnum = riskLevel != null ? RiskLevel.valueOf(riskLevel) : null;
        return ResponseEntity.ok(analystService.listAlerts(statusEnum, riskLevelEnum, limit));
    }

    @GetMapping("/{alertId}")
    public ResponseEntity<AlertDetailResponse> getAlert(@PathVariable UUID alertId) {
        return ResponseEntity.ok(analystService.getAlert(alertId));
    }

    @PostMapping("/{alertId}/approve")
    public ResponseEntity<ReviewDecisionResponse> approveAlert(
            @PathVariable UUID alertId,
            @Valid @RequestBody ReviewDecisionRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID analystUserId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(analystService.approveAlert(alertId, analystUserId, request.reason()));
    }

    @PostMapping("/{alertId}/reject")
    public ResponseEntity<ReviewDecisionResponse> rejectAlert(
            @PathVariable UUID alertId,
            @Valid @RequestBody ReviewDecisionRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        UUID analystUserId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(analystService.rejectAlert(alertId, analystUserId, request.reason()));
    }
}
