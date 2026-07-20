package org.coryd.payment.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/earnings")
@RequiredArgsConstructor
public class EarningsController {

    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;

    @GetMapping("/drivers/{driverId}")
    public ApiResponse<DriverEarningsAccount> account(@PathVariable String driverId) {
        return ApiResponse.ok(earningsAccountRepository.findByDriverId(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("No earnings account yet for this driver")));
    }

    @GetMapping("/drivers/{driverId}/transactions")
    public ApiResponse<List<EarningsTransaction>> transactions(@PathVariable String driverId) {
        return ApiResponse.ok(earningsTransactionRepository.findByDriverId(driverId));
    }
}
