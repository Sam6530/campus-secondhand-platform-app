package com.campus.asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.campus.shared.account.AccountRow;
import com.campus.shared.account.ActorService;
import com.campus.shared.api.ApiException;

/**
 * 共享资源入口（INT-RB-ASSET）：上传保存相对存储键，读取按对象授权。
 * 公开物品图片可匿名读取；认证材料、私有物品与订单图片仍需会话与对象授权。
 */
@Service
public class AssetService {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final AssetMapper assetMapper;
    private final ActorService actorService;
    private final Path uploadDir;
    private final long maxBytes;
    private final long maxPixels;

    public AssetService(AssetMapper assetMapper, ActorService actorService,
                        @Value("${campus.upload.dir}") String uploadDir,
                        @Value("${campus.upload.max-bytes}") long maxBytes,
                        @Value("${campus.upload.max-pixels}") long maxPixels) {
        this.assetMapper = assetMapper;
        this.actorService = actorService;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
    }

    @Transactional
    public String upload(String kind, MultipartFile file) {
        AccountRow actor = actorService.requireNewBusinessAccount();
        validate(kind, file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.badRequest("图片读取失败，请重新上传");
        }
        if (bytes.length == 0) {
            throw ApiException.badRequest("图片为空，不能上传");
        }
        if (bytes.length > maxBytes) {
            throw ApiException.badRequest("图片超过允许的容量上限");
        }
        String contentType = detectContentType(bytes);
        if (contentType == null) {
            throw ApiException.badRequest("仅支持 JPEG 或 PNG 图片");
        }
        checkPixels(bytes);

        String id = UUID.randomUUID().toString();
        String extension = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? ".png" : ".jpg";
        Path target = uploadDir.resolve(id + extension);
        try {
            Files.createDirectories(uploadDir);
            Files.write(target, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("图片保存失败：" + ex.getMessage(), ex);
        }

        AssetRow row = new AssetRow();
        row.id = id;
        row.uploaderId = actor.id;
        row.kind = kind;
        row.storageKey = target.getFileName().toString();
        row.contentType = contentType;
        row.sizeBytes = bytes.length;
        row.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        assetMapper.insert(row);
        return id;
    }


    /** 公开物品图片匿名可读；本人上传的资源、订单当事人快照图片与管理员可按权限读。 */
    public ResponseEntity<Resource> load(String assetId) {
        AssetRow asset = assetMapper.findById(assetId);
        if (asset == null) {
            throw ApiException.notFound("图片不存在");
        }
        Optional<AccountRow> viewer = currentAccount();
        boolean publicItemImage = "ITEM".equals(asset.kind) && assetMapper.countPublicItemUsage(assetId) > 0;
        boolean orderImage = viewer.isPresent()
                && assetMapper.countParticipantOrderUsage(assetId, viewer.get().id) > 0;
        boolean owner = viewer.isPresent() && viewer.get().id.equals(asset.uploaderId);
        boolean admin = viewer.map(AccountRow::isAdmin).orElse(false);
        if (!publicItemImage && !owner && !admin && !orderImage) {
            if (viewer.isEmpty()) {
                throw ApiException.unauthorized("请先登录后读取该图片");
            }
            throw ApiException.forbidden("无权读取该图片");
        }

        Path path = uploadDir.resolve(asset.storageKey).normalize();
        if (!path.startsWith(uploadDir) || !Files.isReadable(path)) {
            throw ApiException.notFound("图片文件不可用");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(asset.contentType))
                    .cacheControl(CacheControl.noStore())
                    .body(new ByteArrayResource(bytes));
        } catch (IOException ex) {
            throw new IllegalStateException("图片读取失败：" + ex.getMessage(), ex);
        }
    }

    private Optional<AccountRow> currentAccount() {
        try {
            return Optional.of(actorService.requireAccount());
        } catch (ApiException ex) {
            return Optional.empty();
        }
    }

    private void validate(String kind, MultipartFile file) {
        if (!"ITEM".equals(kind) && !"VERIFY".equals(kind)) {
            throw ApiException.badRequest("图片用途只能是 ITEM 或 VERIFY");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要上传的图片");
        }
    }

    private String detectContentType(byte[] bytes) {
        if (startsWith(bytes, PNG_MAGIC)) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (startsWith(bytes, JPEG_MAGIC)) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        return null;
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private void checkPixels(byte[] bytes) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw ApiException.badRequest("图片格式无法识别");
            }
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels > maxPixels) {
                throw ApiException.badRequest("图片像素超过允许上限");
            }
        } catch (IOException ex) {
            throw ApiException.badRequest("图片格式无法识别");
        }
    }
}
