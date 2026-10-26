package com.example.notificationservice.service;

public final class RecipientMasker {

    private RecipientMasker() {}

    public static String mask(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "***";
        }
        int atIndex = recipient.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String localPart = recipient.substring(0, atIndex);
        String domainPart = recipient.substring(atIndex);

        if (localPart.length() <= 2) {
            return localPart.charAt(0) + "***" + domainPart;
        }

        return localPart.charAt(0) + "***" + localPart.charAt(localPart.length() - 1) + domainPart;
    }
}
