package com.finmates.predictions.controller;

import com.finmates.predictions.service.UniswapDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/cache")
public class CacheController {
    private final CacheManager cacheManager;
    private final UniswapDataService uniswapDataService;

    public CacheController(CacheManager cacheManager, UniswapDataService uniswapDataService) {
        this.cacheManager = cacheManager;
        this.uniswapDataService = uniswapDataService;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        cacheManager.getCacheNames().forEach(cacheName -> {
            Map<String, Object> cacheStats = new HashMap<>();
            cacheStats.put("name", cacheName);
            stats.put(cacheName, cacheStats);
        });
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/clear")
    public ResponseEntity<String> clearCache() {
        uniswapDataService.clearCache();
        return ResponseEntity.ok("Cache cleared successfully");
    }
}
