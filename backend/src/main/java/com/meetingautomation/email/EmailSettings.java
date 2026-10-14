package com.meetingautomation.email;

public record EmailSettings(String provider, String clientId, String clientSecret, String refreshToken,
        String senderAddress) {
    private static final java.util.regex.Pattern ADDRESS_PATTERN = java.util.regex.Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    public boolean configured() {
        return "GMAIL_API".equals(provider) && nonblank(clientId) && nonblank(clientSecret)
                && nonblank(refreshToken) && validAddress(senderAddress);
    }

    public static boolean validAddress(String value) {
        if (value == null) return false;
        String address = value.trim();
        int at = address.indexOf('@');
        return at > 0 && at <= 64 && address.length() <= 254 && !address.startsWith(".")
                && address.charAt(at - 1) != '.' && !address.contains("..")
                && ADDRESS_PATTERN.matcher(address).matches();
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "EmailSettings[configured=" + configured() + "]";
    }
}
