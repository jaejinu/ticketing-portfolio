package com.ticketing.paymentsaga.application;

import com.ticketing.paymentsaga.api.dto.PaymentResponse;
import com.ticketing.paymentsaga.domain.Payment;
import com.ticketing.paymentsaga.domain.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read-only operational totals, independent of the currently displayed page. KRW, no settlement fees. */
@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class PaymentReportService {
    private final PaymentRepository payments;
    private final EntityManager em;
    public PaymentReportService(PaymentRepository payments, EntityManager em) {
        this.payments = payments; this.em = em;
    }
    public record Bucket(String status, boolean refundPending, long count, long amount) {}
    public record Report(List<PaymentResponse> payments, long totalElements, int totalPages, int page,
                         List<Bucket> totals) {}
    public Report search(UUID scheduleId, UUID holderId, int page, int size) {
        Specification<Payment> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (scheduleId != null) predicates.add(cb.equal(root.get("scheduleId"), scheduleId));
            if (holderId != null) predicates.add(cb.equal(root.get("holderId"), holderId));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var found = payments.findAll(filter, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(Object[].class);
        var root = query.from(Payment.class);
        query.multiselect(root.get("status"), root.get("refundRequested"), cb.count(root), cb.sum(root.<Long>get("amount")))
                .where(filter.toPredicate(root, query, cb)).groupBy(root.get("status"), root.get("refundRequested"))
                .orderBy(cb.asc(root.get("status")), cb.asc(root.get("refundRequested")));
        var totals = em.createQuery(query).getResultList().stream().map(row -> new Bucket(
                (String) row[0], "APPROVED".equals(row[0]) && Boolean.TRUE.equals(row[1]),
                ((Number) row[2]).longValue(), ((Number) row[3]).longValue())).toList();
        Map<String, Bucket> merged = new LinkedHashMap<>();
        for (Bucket bucket : totals) {
            merged.merge(bucket.status() + ":" + bucket.refundPending(), bucket,
                    (a, b) -> new Bucket(a.status(), a.refundPending(), a.count() + b.count(), a.amount() + b.amount()));
        }
        return new Report(found.getContent().stream().map(PaymentResponse::of).toList(),
                found.getTotalElements(), found.getTotalPages(), page, List.copyOf(merged.values()));
    }
}
