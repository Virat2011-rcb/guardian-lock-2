package com.morningsearch.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationEngineTest {
    @Test fun firstTriggerIsTwoHours() {
        val plan = EscalationEngine.plan(0)
        assertEquals(2L * 60L * 60L * 1000L, plan.durationMs)
        assertFalse(plan.includeEntertainment)
    }

    @Test fun secondTriggerIsSixHours() {
        assertEquals(6L * 60L * 60L * 1000L, EscalationEngine.plan(1).durationMs)
    }

    @Test fun thirdTriggerIsTwelveHoursAndIncludesEntertainment() {
        val plan = EscalationEngine.plan(2)
        assertEquals(12L * 60L * 60L * 1000L, plan.durationMs)
        assertTrue(plan.includeEntertainment)
    }

    @Test fun repeatedTriggersCapAtFortyEightHours() {
        assertEquals(48L * 60L * 60L * 1000L, EscalationEngine.plan(99).durationMs)
    }
}
