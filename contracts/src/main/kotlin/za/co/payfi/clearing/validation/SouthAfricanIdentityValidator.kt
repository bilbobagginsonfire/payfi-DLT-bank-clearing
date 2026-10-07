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
     * Luhn algorithm over all 13 digits:
     * 1. Number positions from the rightmost digit (check digit Z = position 0)
     * 2. Double every digit at an odd position
     * 3. If doubled > 9, subtract 9
     * 4. Sum all processed digits
     * 5. Valid when sum mod 10 == 0
     *
     * Synthetic valid IDs (see specs/verified-sa-ids.txt):
     * 9001015009086, 8506150123089, 7703125432080, 9502280456183
     */
    fun isValidSaId(idNumber: String): Boolean {
        if (idNumber.length != 13 || !idNumber.all { it.isDigit() }) return false
        if (!isValidDateOfBirth(idNumber.substring(0, 6))) return false
        val citizenship = idNumber[10].digitToInt()
        if (citizenship != 0 && citizenship != 1) return false
        return isValidLuhn(idNumber)
    }

    private fun isValidLuhn(idNumber: String): Boolean {
        val sum = idNumber.reversed().mapIndexed { position, char ->
            val digit = char.digitToInt()
            if (position % 2 == 1) {
                val doubled = digit * 2
                if (doubled > 9) doubled - 9 else doubled
            } else {
                digit
            }
        }.sum()
        return sum % 10 == 0
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

