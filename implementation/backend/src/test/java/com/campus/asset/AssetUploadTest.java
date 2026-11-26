package com.campus.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import com.campus.shared.api.ApiException;
import com.campus.support.TestActorConfig;
import com.campus.support.TestActors;
import com.campus.support.TestFixtures;

/**
 * 资源入口自测：认证材料允许未认证用户上传（否则无法提交认证）、物品图片要求已认证、
 * 伪类型/超限拒绝、私有图片匿名读取被拒、公开物品图片可匿名读取。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class AssetUploadTest {

    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final Path UPLOAD_DIR = Paths.get("target/test-uploads");

    @Autowired
    private AssetService assetService;
    @Autowired
    private AssetMapper assetMapper;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbc;

    private TestFixtures.Account unverified;
    private TestFixtures.Account verified;
    private TestFixtures.Account restricted;

    @BeforeEach
    void setUp() throws IOException {
        fixtures.reset();
        TestActors.logout();
        unverified = fixtures.createUser("newuser01", "未认证用户", 0, 0);
        verified = fixtures.createUser("seller01", "已认证卖方", 1, 0);
        restricted = fixtures.createUser("banned01", "受限用户", 1, 1);
        cleanUploads();
    }

    @AfterEach
    void tearDown() throws IOException {
        TestActors.logout();
        cleanUploads();
    }

    @Test
    @DisplayName("未认证用户可以上传认证材料（VERIFY），否则 FR02 认证流程走不通")
    void unverifiedUserCanUploadVerifyAsset() {
        TestActors.login(unverified.id());
        String id = assetService.upload("VERIFY", png());
        assertThat(id).isNotBlank();
        assertThat(assetMapper.findById(id).kind).isEqualTo("VERIFY");
        assertThat(assetMapper.findById(id).uploaderId).isEqualTo(unverified.id());
    }

    @Test
    @DisplayName("未认证用户不能上传物品图片（ITEM）")
    void unverifiedUserCannotUploadItemAsset() {
        TestActors.login(unverified.id());
        assertApiError(() -> assetService.upload("ITEM", png()), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("assets", null)).isZero();
    }

    @Test
    @DisplayName("已认证用户上传物品图片成功，元数据与文件都落位")
    void verifiedUserCanUploadItemAsset() throws IOException {
        TestActors.login(verified.id());
        String id = assetService.upload("ITEM", png());
        AssetRow row = assetMapper.findById(id);
        assertThat(row.contentType).isEqualTo("image/png");
        assertThat(row.sizeBytes).isEqualTo(PNG.length);
        assertThat(Files.exists(UPLOAD_DIR.resolve(row.storageKey))).isTrue();
    }

    @Test
    @DisplayName("用途非法、空文件、伪图片、超限都被拒绝")
    void rejectInvalidUploads() {
        TestActors.login(verified.id());
        assertApiError(() -> assetService.upload("AVATAR", png()), HttpStatus.BAD_REQUEST);
        assertApiError(() -> assetService.upload("ITEM",
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])), HttpStatus.BAD_REQUEST);
        assertApiError(() -> assetService.upload("ITEM",
                new MockMultipartFile("file", "fake.png", "image/png", new byte[] {1, 2, 3, 4})),
                HttpStatus.BAD_REQUEST);
        byte[] tooLarge = new byte[5242881];
        assertApiError(() -> assetService.upload("ITEM",
                new MockMultipartFile("file", "big.png", "image/png", tooLarge)), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("assets", null)).isZero();
    }

    @Test
    @DisplayName("受限账户不能上传")
    void restrictedAccountCannotUpload() {
        TestActors.login(restricted.id());
        assertApiError(() -> assetService.upload("VERIFY", png()), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("私有图片匿名读取 403；公开物品图片匿名可读")
    void privateAndPublicRead() {
        TestActors.login(verified.id());
        String privateAsset = assetService.upload("ITEM", png());
        String publicAsset = assetService.upload("ITEM", png());
        String itemId = fixtures.createSellableItem(verified.id(), "公开物品I1", "10.00");
        jdbc.update("UPDATE item_versions SET image_id = ? WHERE item_id = ?", publicAsset, itemId);

        TestActors.logout();
        assertApiError(() -> assetService.load(privateAsset), HttpStatus.FORBIDDEN);
        assertThat(assetService.load(publicAsset).getStatusCode().value()).isEqualTo(200);
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "photo.png", "image/png", PNG);
    }

    private void cleanUploads() throws IOException {
        if (!Files.isDirectory(UPLOAD_DIR)) {
            return;
        }
        try (var paths = Files.list(UPLOAD_DIR)) {
            for (Path path : paths.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertApiError(ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(expected));
    }
}
