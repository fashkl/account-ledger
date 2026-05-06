package com.mohamedali.ledger.ledger.adapter.in.web.order;

import com.mohamedali.ledger.ledger.adapter.in.web.order.dto.OrderLifecycleRequest;
import com.mohamedali.ledger.ledger.application.port.in.order.BuyingPowerQuery;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderLifecycleController {

    private final OrderLifecycleUseCase lifecycleUseCase;
    private final BuyingPowerQuery buyingPowerQuery;

    public OrderLifecycleController(OrderLifecycleUseCase lifecycleUseCase, BuyingPowerQuery buyingPowerQuery) {
        this.lifecycleUseCase = lifecycleUseCase;
        this.buyingPowerQuery = buyingPowerQuery;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> handle(@Valid @RequestBody OrderLifecycleRequest request) {
        lifecycleUseCase.handle(request.toCommand());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/buying-power/{customerId}")
    public ResponseEntity<Map<String, Object>> buyingPower(@PathVariable UUID customerId,
                                                            @RequestParam(defaultValue = "AED") Currency currency) {
        BigDecimal value = buyingPowerQuery.buyingPower(customerId, currency);
        return ResponseEntity.ok(Map.of("customerId", customerId, "currency", currency, "buyingPower", value));
    }
}
