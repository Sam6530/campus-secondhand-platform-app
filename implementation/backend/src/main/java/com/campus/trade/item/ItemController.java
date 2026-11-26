package com.campus.trade.item;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public List<PublicItemDto> search(@RequestParam(value = "q", required = false) String q,
                                      @RequestParam(value = "category", required = false) String category,
                                      @RequestParam(value = "page", defaultValue = "1") int page) {
        return itemService.search(q, category, page);
    }

    @GetMapping("/mine")
    public List<ItemDto> mine() {
        return itemService.listMine();
    }

    @PostMapping
    public ItemDto create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          @RequestBody(required = false) ItemInput input) {
        return itemService.create(input, idempotencyKey);
    }

    @PutMapping("/{id}")
    public ItemDto update(@PathVariable("id") String id,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          @RequestBody(required = false) ItemUpdateInput input) {
        return itemService.update(id, input, idempotencyKey);
    }

    @PostMapping("/{id}/withdraw")
    public Map<String, Boolean> withdraw(@PathVariable("id") String id) {
        itemService.withdraw(id);
        return Map.of("ok", true);
    }
}
