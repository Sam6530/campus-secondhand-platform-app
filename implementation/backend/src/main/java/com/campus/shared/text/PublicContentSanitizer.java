package com.campus.shared.text;

import java.util.regex.Pattern;

/**
 * 公开内容脱敏（BR14）：公开列表/详情中的标题、描述与公开昵称在返回前把联系方式替换为占位，
 * 数据库中原文不改写；私有会话与本人/治理查询保持原文。
 */
public final class PublicContentSanitizer {

    private static final String MASK = "[联系方式已隐藏]";
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)0\\d{2,3}-?\\d{7,8}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern QQ = Pattern.compile("(?i)(?:qq|微信|wechat|weixin)\\s*[:：]?\\s*\\d{5,12}");

    private PublicContentSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = MOBILE.matcher(text).replaceAll(MASK);
        result = LANDLINE.matcher(result).replaceAll(MASK);
        result = EMAIL.matcher(result).replaceAll(MASK);
        result = QQ.matcher(result).replaceAll(MASK);
        return result;
    }
}
