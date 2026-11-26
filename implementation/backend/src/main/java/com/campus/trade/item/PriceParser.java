package com.campus.trade.item;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.campus.shared.api.ApiException;

/**
 * 价格解析（BR02）：人民币元正数，最多两位小数，最大 9999999999.99。
 * 以字符串接收并显式校验，避免浮点误差与静默截断。
 */
public final class PriceParser {

    private static final BigDecimal MAX = new BigDecimal("9999999999.99");

    private PriceParser() {
    }

    public static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("价格不能为空");
        }
        String text = raw.trim();
        if (!text.matches("\\d{1,10}(\\.\\d{1,2})?")) {
            throw ApiException.badRequest("价格必须为正数且最多两位小数");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.signum() <= 0) {
            throw ApiException.badRequest("价格必须为正数");
        }
        if (value.compareTo(MAX) > 0) {
            throw ApiException.badRequest("价格超出允许范围");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
