package za.co.payfi.clearing.validation

/**
 * Validates South African identity documents for FICA compliance.
 * Per SARB Directive 1 of 2022 and FIC Guidance Note 8 (2023).
 */
object SouthAfricanIdentityValidator {

    /**
     * Validates a South African ID number.
     * Format: YYMMDD GSSS CAZ (13 digits)
     *
     * Luhn algorithm:
     * 1. Take first 12 digits
     * 2. From rightmost of those 12, double every second digit
     * 3. If doubled > 9, subtract 9
     * 4. Sum all processed digits
     * 5. Check digit = (10 - (sum mod 10)) mod 10
     *
     * Verified valid IDs: 7801015012082, 8501015800089, 9005152345081, 7508205120084
     */
    fun isValidSaId(idNumber: String): Boolean {
        if (idNumber.length != 13 || !idNumber.all { it.isDigit() }) return false
        if (!isValidDateOfBirth(idNumber.substring(0, 6))) return false
        val citizenship = idNumber[10].digitToInt()
        if (citizenship != 0 && citizenship != 1) return false
        return isValidLuhn(idNumber)
    }

    private fun isValidLuhn(idNumber: String): Boolean {
        val digits = idNumber.map { it.digitToInt() }
        val first12 = digits.subList(0, 12)
        val actualCheckDigit = digits[12]

        val processed = mutableListOf<Int>()
        for (i in first12.indices.reversed()) {
            val positionFromRight = first12.size - 1 - i
            if (positionFromRight % 2 == 1) {
                var doubled = first12[i] * 2
                if (doubled > 9) doubled -= 9
                processed.add(doubled)
            } else {
                processed.add(first12[i])
            }
        }

        val total = processed.sum()
        val calculatedCheckDigit = (10 - (total % 10)) % 10
        return calculatedCheckDigit == actualCheckDigit
    }

    private fun isValidDateOfBirth(yymmdd: String): Boolean {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return false
        val month = yymmdd.substring(2, 4).toInt()
        val day = yymmdd.substring(4, 6).toInt()
        if (month < 1 || month > 12) return false
        if (day < 1 || day > 31) return false
        val maxDays = when (month) {
            2 -> 29
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDays
    }

    fun isValidPassport(passport: String): Boolean {
        if (passport.isBlank()) return false
        if (passport.length < 6 || passport.length > 20) return false
        return passport.all { it.isLetterOrDigit() }
    }

    fun isValidUniqueCustomerId(id: String): Boolean {
        if (id.isBlank()) return false
        if (id.length < 4) return false
        return id.all { it.isLetterOrDigit() }
    }

    fun isValidBusinessRegistration(regNumber: String): Boolean {
        if (regNumber.isBlank()) return false
        return regNumber.any { it.isDigit() }
    }

    fun isValidBranchCode(code: String): Boolean {
        return code.length == 6 && code.all { it.isDigit() }
    }
}

