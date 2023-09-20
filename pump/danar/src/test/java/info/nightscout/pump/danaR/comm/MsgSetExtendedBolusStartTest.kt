package info.nightscout.pump.danaR.comm

import info.nightscout.androidaps.danar.comm.MsgSetExtendedBolusStart
import info.nightscout.core.constraints.ConstraintObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`

class MsgSetExtendedBolusStartTest : DanaRTestBase() {

    @Test fun runTest() {
        `when`(constraintChecker.applyBolusConstraints(anyObject())).thenReturn(ConstraintObject(0.0, injector))
        val packet = MsgSetExtendedBolusStart(injector, 2.0, 2.toByte())

        // test message decoding
        packet.handleMessage(createArray(34, 7.toByte()))
        Assertions.assertEquals(true, packet.failed)
    }

}