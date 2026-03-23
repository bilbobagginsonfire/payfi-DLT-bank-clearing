package za.co.payfi.clearing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import za.co.payfi.clearing.validation.SouthAfricanIdentityValidator

class SouthAfricanIdentityValidatorTest {

    @Nested inner class SaIdValidation {
        @Test fun `valid SA ID - 7801015012082`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("7801015012082")) }
        @Test fun `valid SA ID - 8501015800089`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("8501015800089")) }
        @Test fun `valid SA ID - 9005152345081`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("9005152345081")) }
        @Test fun `valid SA ID - 7508205120084`() { assertTrue(SouthAfricanIdentityValidator.isValidSaId("7508205120084")) }
        @Test fun `invalid SA ID - 8501015800085`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("8501015800085")) }
        @Test fun `invalid SA ID - 8501015800088`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("8501015800088")) }
        @Test fun `invalid SA ID - too short`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("780101501208")) }
        @Test fun `invalid SA ID - too long`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("78010150120821")) }
        @Test fun `invalid SA ID - contains letters`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("780101A012082")) }
        @Test fun `invalid SA ID - empty`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("")) }
        @Test fun `invalid SA ID - month 13`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7813015012082")) }
        @Test fun `invalid SA ID - month 00`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7800015012082")) }
        @Test fun `invalid SA ID - day 32`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7801325012082")) }
        @Test fun `invalid SA ID - day 00`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7801005012082")) }
        @Test fun `invalid SA ID - wrong check 0`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7801015012080")) }
        @Test fun `invalid SA ID - wrong check 5`() { assertFalse(SouthAfricanIdentityValidator.isValidSaId("7801015012085")) }
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
