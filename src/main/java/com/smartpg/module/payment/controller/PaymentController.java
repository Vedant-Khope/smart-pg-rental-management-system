package com.smartpg.module.payment.controller;

import com.smartpg.module.payment.dto.request.RecordCashPaymentRequest;
import com.smartpg.module.payment.dto.response.PaymentResponse;
import com.smartpg.module.payment.model.Payment;
import com.smartpg.module.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * API to record a manual cash payment.
     * Expected to be called by users with the OWNER role.
     * 
     * @param request Validated JSON body mapped to DTO.
     * @return PaymentResponse DTO containing the newly created transaction details.
     */
    @PostMapping("/cash")
    public ResponseEntity<PaymentResponse> recordCashPayment(@Valid @RequestBody RecordCashPaymentRequest request) {
        
        Payment payment = paymentService.recordCashPayment(
                request.getRentLedgerId(),
                request.getPaidByUserId(),
                request.getAmount()
        );
        
        return ResponseEntity.ok(mapToResponse(payment));
    }

    /**
     * Helper method to map Entity to DTO.
     * In a larger project, you would use a library like MapStruct for this,
     * but manual mapping is perfectly fine and often faster to compile/debug.
     */
    private PaymentResponse mapToResponse(Payment payment) {
        PaymentResponse response = new PaymentResponse();
        response.setId(payment.getId());
        response.setRentLedgerId(payment.getRentLedger().getId());
        response.setAmount(payment.getAmount());
        response.setPaymentMethod(payment.getPaymentMethod().name());
        response.setStatus(payment.getStatus().name());
        response.setTransactionReference(payment.getTransactionReference());
        response.setPaymentDate(payment.getPaymentDate());
        return response;
    }
}
