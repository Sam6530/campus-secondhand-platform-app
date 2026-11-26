package com.campus.shared.text;

import com.campus.shared.api.ApiException;

/** 审核与治理理由遵循OpenAPI的1500个Unicode码点上限。 */
public final class ReviewReason {
    private ReviewReason() {}

    public static String require(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 1500) {
            throw ApiException.badRequest("审核理由需为1至1500字");
        }
        return reason;
    }
}
