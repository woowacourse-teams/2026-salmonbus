package com.gustler.backend.routegeometry.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ItsTurnEvidenceTest {
    private ItsTurnEvidence row(String type, String operation) {
        return new ItsTurnEvidence("1790007102", "1", "1790687601", "1790687501", type, operation);
    }

    @Test void decodesAllOfficialTypesWithoutTurningMovementDescriptionsIntoBans() {
        String[] codes = {"001", "002", "003", "011", "012", "101", "102", "103"};
        ItsTurnEvidence.Meaning[] expected = {
            ItsTurnEvidence.Meaning.UNPROTECTED_TURN, ItsTurnEvidence.Meaning.BUS_ONLY_TURN,
            ItsTurnEvidence.Meaning.TURN_PROHIBITED, ItsTurnEvidence.Meaning.U_TURN,
            ItsTurnEvidence.Meaning.P_TURN, ItsTurnEvidence.Meaning.LEFT_PROHIBITED,
            ItsTurnEvidence.Meaning.STRAIGHT_PROHIBITED, ItsTurnEvidence.Meaning.RIGHT_PROHIBITED};
        for (int i = 0; i < codes.length; i++) assertEquals(expected[i], row(codes[i], "0").meaning());
        for (String code : new String[] {"001", "002", "011", "012"}) assertFalse(row(code, "0").describesProhibition());
        for (String code : new String[] {"003", "101", "102", "103"}) assertTrue(row(code, "0").describesProhibition());
    }

    @Test void partialTimeNeverBecomesAnAllDayRestriction() {
        var row = row("101", "1");
        assertEquals(ItsTurnEvidence.Operation.PART_TIME, row.operation());
        assertTrue(row.requiresScheduleEvidence());
        assertEquals(ItsTurnEvidence.Operation.ALL_DAY, row("101", "0").operation());
    }

    @Test void unknownAndUnpaddedCodesRemainUnknownAndPreserved() {
        var row = row("11", "9");
        assertEquals("11", row.typeCode());
        assertEquals(ItsTurnEvidence.Meaning.UNKNOWN, row.meaning());
        assertEquals(ItsTurnEvidence.Operation.UNKNOWN, row.operation());
        assertFalse(row.describesProhibition());
    }

    @Test void preservesNonincidentEndLinksInsteadOfInventingDirectConnectivity() {
        var row = row("011", "0");
        assertEquals("1790687501", row.endLink());
        assertNotEquals(row.nodeId(), row.endLink());
        assertThrows(IllegalArgumentException.class,
                () -> new ItsTurnEvidence("17900071020001", "1", row.startLink(), row.endLink(), "011", "0"));
    }
}
