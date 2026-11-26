package com.campus.trade.order;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderDto> list() {
        return orderService.list();
    }

    @PostMapping
    public OrderDto create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                           @RequestBody(required = false) CreateOrderInput input) {
        return orderService.create(input, idempotencyKey);
    }

    @PostMapping("/{id}/{action}")
    public OrderDto act(@PathVariable("id") String id,
                        @PathVariable("action") String action,
                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                        @RequestBody(required = false) OrderActionInput input) {
        return orderService.act(id, action, input, idempotencyKey);
    }

    @GetMapping("/{id}/details")
    public OrderDetailsDto details(@PathVariable("id") String id) {
        return orderService.details(id);
    }
}
