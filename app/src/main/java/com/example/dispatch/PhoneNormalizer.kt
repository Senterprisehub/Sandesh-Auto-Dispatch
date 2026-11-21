package com.example.dispatch

object PhoneNormalizer {
    /**
     * Normalizes Indian and international phone numbers to digits-only E.164 format (without '+')
     * suitable for whatsapp://send?phone=...
     */
    fun normalizeForWhatsApp(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val digitsOnly = trimmed.replace(Regex("[^0-9]"), "")
        if (digitsOnly.isEmpty()) return ""

        // Strip leading international 00 prefix if present
        val withoutDoubleZero = if (digitsOnly.startsWith("00") && digitsOnly.length > 10) {
            digitsOnly.drop(2)
        } else {
            digitsOnly
        }

        // Indian 11-digit local trunk format: 09876543210 -> 919876543210
        if (withoutDoubleZero.length == 11 && withoutDoubleZero.startsWith("0")) {
            val tenDigit = withoutDoubleZero.drop(1)
            if (tenDigit.firstOrNull() in '6'..'9') {
                return "91$tenDigit"
            }
        }

        // Standard 10-digit Indian mobile number -> auto-prefix 91
        if (withoutDoubleZero.length == 10 && withoutDoubleZero.firstOrNull() in '6'..'9') {
            return "91$withoutDoubleZero"
        }

        return withoutDoubleZero
    }

    fun formatDisplayPhone(raw: String): String {
        val normalized = normalizeForWhatsApp(raw)
        if (normalized.isEmpty()) return raw
        return if (normalized.length == 12 && normalized.startsWith("91")) {
            "+91 ${normalized.substring(2, 7)} ${normalized.substring(7)}"
        } else {
            "+$normalized"
        }
    }

    fun isValidPhoneInput(raw: String): Boolean {
        val normalized = normalizeForWhatsApp(raw)
        return normalized.length in 10..15
    }
}
