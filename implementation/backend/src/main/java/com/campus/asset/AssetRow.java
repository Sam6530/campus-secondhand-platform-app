package com.campus.asset;

import java.time.LocalDateTime;

/** assets 行：上传人、用途、相对存储键与类型；读取按关联对象授权。 */
public class AssetRow {

    public String id;
    public String uploaderId;
    public String kind;
    public String storageKey;
    public String contentType;
    public long sizeBytes;
    public LocalDateTime createdAt;
}
