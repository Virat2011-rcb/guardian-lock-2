package com.morningsearch.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchClassifierTest {
    @Test fun detectsLeetspeakAndMisspelling() {
        assertTrue(SearchClassifier.shouldBlock("p0rnhub"))
        assertTrue(SearchClassifier.shouldBlock("pornograpy"))
    }

    @Test fun detectsHindiAndHinglish() {
        assertNotNull(SearchClassifier.classify("पोर्न वीडियो"))
        assertNotNull(SearchClassifier.classify("nangi video"))
    }

    @Test fun allowsRecoveryAndMedicalIntent() {
        assertFalse(SearchClassifier.shouldBlock("porn addiction recovery help"))
        assertFalse(SearchClassifier.shouldBlock("masturbation addiction therapist"))
    }
}
