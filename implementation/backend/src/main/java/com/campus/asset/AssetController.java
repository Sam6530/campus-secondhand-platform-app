package com.campus.asset;

import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping("/api/assets")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam("kind") String kind) {
        return Map.of("id", assetService.upload(kind, file));
    }

    @GetMapping("/api/assets/{id}")
    public ResponseEntity<Resource> read(@PathVariable("id") String id) {
        return assetService.load(id);
    }
}
