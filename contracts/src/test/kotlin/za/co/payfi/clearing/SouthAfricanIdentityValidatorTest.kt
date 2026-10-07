package za.co.payfi.clearing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import za.co.payfi.clearing.validation.SouthAfricanIdentityValidator

class SouthAfricanIdentityValidatorTest {

    @Nested inner class SaIdValidation {
        @Test fun `valid SA ID - 9001015009086`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("9001015009086")) }
        @Test fun `valid SA ID - 8506150123089`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("8506150123089")) }
        @Test fun `valid SA ID - 7703125432080`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("7703125432080")) }
        @Test fun `valid SA ID - 9502280456183 permanent resident`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("9502280456183")) }
        @Test fun `valid SA ID - 8001015009087`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("8001015009087")) }
        @Test fun `invalid SA ID - wrong check 0`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9001015009080")) }
        @Test fun `invalid SA ID - wrong check 5`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9001015009085")) }
        @Test fun `invalid SA ID - wrong check 7`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("8506150123087")) }
        @Test fun `invalid SA ID - only valid under non-standard Luhn`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7801015012082")) }
        @Test fun `invalid SA ID - too short`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("900101500908")) }
        @Test fun `invalid SA ID - too long`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("90010150090861")) }
        @Test fun `invalid SA ID - contains letters`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("900101A009086")) }
        @Test fun `invalid SA ID - empty`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("")) }
        @Test fun `invalid SA ID - month 13`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9013015009086")) }
        @Test fun `invalid SA ID - month 00`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9000015009086")) }
        @Test fun `invalid SA ID - day 32`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9001325009086")) }
        @Test fun `invalid SA ID - day 00`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9001005009086")) }
        @Test fun `invalid SA ID - citizenship 2`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("9001015009284")) }
    }

    @Nested inner class PassportValidation {
        @Test fun `valid passport`() { assertTrue(SouthAfricanIdentityValidator.isValidPassport("A12345678")) }
        @Test fun `valid passport min`() { assertTrue(SouthAfricanIdentityValidator.isValidPassport("AB1234")) }
        @Test fun `invalid passport short`() { assertFalse(SouthAfricanIdentityValidator.isValidPassport("AB123")) }
        @Test fun `invalid passport blank`() { assertFalse(SouthAfricanIdentityValidator.isValidPassport("")) }
        @Test fun `invalid passport special`() { assertFalse(SouthAfricanIdentityValidator.isValidPassport("A123-456")) }
    }

    @Nested inner class UniqueCustomerIdValidation {
        @Test fun `valid`() { assertTrue(SouthAfricanIdentityValidator.isValidUniqueCustomerId("CUST12345")) }
        @Test fun `valid min`() { assertTrue(SouthAfricanIdentityValidator.isValidUniqueCustomerId("AB12")) }
        @Test fun `invalid short`() { assertFalse(SouthAfricanIdentityValidator.isValidUniqueCustomerId("AB1")) }
        @Test fun `invalid blank`() { assertFalse(SouthAfricanIdentityValidator.isValidUniqueCustomerId("")) }
    }

    @Nested inner class BusinessRegValidation {
        @Test fun `valid standard`() { assertTrue(SouthAfricanIdentityValidator.isValidBusinessRegistration("2015/123456/07")) }
        @Test fun `valid digits only`() { assertTrue(SouthAfricanIdentityValidator.isValidBusinessRegistration("2015123456")) }
        @Test fun `invalid blank`() { assertFalse(SouthAfricanIdentityValidator.isValidBusinessRegistration("")) }
        @Test fun `invalid no digits`() { assertFalse(SouthAfricanIdentityValidator.isValidBusinessRegistration("ABCDEF")) }
    }

    @Nested inner class BranchCodeValidation {
        @Test fun `valid`() { assertTrue(SouthAfricanIdentityValidator.isValidBranchCode("250655")) }
        @Test fun `valid leading zeros`() { assertTrue(SouthAfricanIdentityValidator.isValidBranchCode("051001")) }
        @Test fun `invalid short`() { assertFalse(SouthAfricanIdentityValidator.isValidBranchCode("25065")) }
        @Test fun `invalid long`() { assertFalse(SouthAfricanIdentityValidator.isValidBranchCode("2506551")) }
        @Test fun `invalid letters`() { assertFalse(SouthAfricanIdentityValidator.isValidBranchCode("25065A")) }
        @Test fun `invalid empty`() { assertFalse(SouthAfricanIdentityValidator.isValidBranchCode("")) }
    }
}

