package com.olive.commerce.common.util;

/**
 * PII 마스킹 유틸리티 (OLV-063).
 * <p>
 * PRD §14.3에 따라 민감 정보를 마스킹합니다.
 */
public final class PIIMasker {

    private PIIMasker() {}

    /**
     * 이름 마스킹 (예: 홍길동 → 홍**).
     *
     * @param name 전체 이름
     * @return 마스킹된 이름 (성 제외 나머지 *)
     */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return "*";
        }
        // 첫 글자만 남기고 나머지 마스킹
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /**
     * 휴대폰 번호 마스킹 (예: 010-1234-5678 → 010-****-5678).
     *
     * @param phone 전화번호
     * @return 마스킹된 전화번호 (가운데 4자리 *)
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        // 하이픈 제거하고 마스킹
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return phone;
        }

        // 010-1234-5678 형식 가정
        if (digits.length() == 10 || digits.length() == 11) {
            int middleStart = 3;
            int middleEnd = digits.length() - 4;
            String masked = digits.substring(0, middleStart) +
                    "****" +
                    digits.substring(middleEnd);
            // 하이픈 복원
            if (phone.contains("-")) {
                return masked.substring(0, 3) + "-" + masked.substring(3, 7) + "-" + masked.substring(7);
            }
            return masked;
        }

        return phone;
    }

    /**
     * 주소 마스킹 (예: 서울시 강남구 역삼동 → 서울시 **구 **동).
     *
     * @param address 주소
     * @return 마스킹된 주소 (구/동 이름 *)
     */
    public static String maskAddress(String address) {
        if (address == null || address.isEmpty()) {
            return address;
        }
        // 공백으로 구분된 단어 중 2번째, 3번째 단어 마스킹
        String[] parts = address.split("\\s+");
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                masked.append(" ");
            }
            if (i >= 1 && i <= 2 && parts[i].length() > 0) {
                // 구, 동 마스킹 (첫 글자만 남김)
                masked.append(parts[i].charAt(0)).append("**");
            } else {
                masked.append(parts[i]);
            }
        }
        return masked.toString();
    }
}
