package com.ticketing.paymentsaga.api;

import com.ticketing.paymentsaga.application.PaymentReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/payment-report")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminPaymentReportController {
    private final PaymentReportService reports;
    public AdminPaymentReportController(PaymentReportService reports) { this.reports = reports; }
    @GetMapping
    public PaymentReportService.Report report(@RequestParam(required = false) UUID scheduleId,
            @RequestParam(required = false) UUID holderId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return reports.search(scheduleId, holderId, page, size);
    }
}
