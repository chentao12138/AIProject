package com.aistudy.server.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * F-15 — DB-free unit tests for {@link RangeHeaderParser}: the four
 * wire cases (valid single range, suffix/open-ended forms, bad unit,
 * multi-range, unsatisfiable, unrecognized) plus the Content-Range
 * formatting used by both RAW download controllers.
 */
class RangeHeaderParserTest {

    @Test
    void absentOrBlankHeaderIsFullRequest() {
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse(null, 100).disposition());
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse("", 100).disposition());
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse("   ", 100).disposition());
    }

    @Test
    void explicitWindowIsSatisfied() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=0-49", 100);
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(0, r.start());
        assertEquals(49, r.endInclusive());
        assertEquals(50, r.length());
    }

    @Test
    void openEndedWindowRunsToEndOfObject() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=50-", 100);
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(50, r.start());
        assertEquals(99, r.endInclusive());
    }

    @Test
    void suffixWindowIsLastNBytes() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=-30", 100);
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(70, r.start());
        assertEquals(99, r.endInclusive());
    }

    @Test
    void suffixLongerThanObjectClampsToZero() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=-30", 10);
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(0, r.start());
        assertEquals(9, r.endInclusive());
    }

    @Test
    void endPastObjectIsClampedNotRejected() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=0-1999", 100);
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(0, r.start());
        assertEquals(99, r.endInclusive());
    }

    @Test
    void startAtOrAfterEofIsUnsatisfiable() {
        assertEquals(RangeHeaderParser.Disposition.UNSATISFIABLE,
                RangeHeaderParser.parse("bytes=100-", 100).disposition());
        assertEquals(RangeHeaderParser.Disposition.UNSATISFIABLE,
                RangeHeaderParser.parse("bytes=150-200", 100).disposition());
    }

    @Test
    void zeroLengthObjectSatisfiesNothing() {
        assertEquals(RangeHeaderParser.Disposition.UNSATISFIABLE,
                RangeHeaderParser.parse("bytes=0-10", 0).disposition());
    }

    @Test
    void suffixOfZeroIsUnsatisfiable() {
        assertEquals(RangeHeaderParser.Disposition.UNSATISFIABLE,
                RangeHeaderParser.parse("bytes=-0", 100).disposition());
    }

    @Test
    void multiRangeIsUnsatisfiableInV1() {
        // Documented V1 rule: single range only, no multipart/byteranges.
        assertEquals(RangeHeaderParser.Disposition.UNSATISFIABLE,
                RangeHeaderParser.parse("bytes=0-10,20-30", 100).disposition());
    }

    @Test
    void foreignUnitIsUnsatisfiable() {
        assertEquals(RangeHeaderParser.Disposition.UNSATISFIABLE,
                RangeHeaderParser.parse("items=0-10", 100).disposition());
    }

    @Test
    void unrecognizedSyntaxIsIgnoredPerRfc9110() {
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse("garbage", 100).disposition());
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse("bytes=abc-def", 100).disposition());
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse("bytes=50-10", 100).disposition());
        assertEquals(RangeHeaderParser.Disposition.FULL_REQUEST,
                RangeHeaderParser.parse("bytes=", 100).disposition());
    }

    @Test
    void bytesTokenIsCaseInsensitiveAndSpacesAllowed() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("BYTES= 0 - 9 ", 100);
        // "0 - 9" with spaces around the dash: bounds are trimmed.
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(0, r.start());
        assertEquals(9, r.endInclusive());
    }

    @Test
    void contentRangeFormatting() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=2-4", 10);
        assertEquals("bytes 2-4/10", RangeHeaderParser.contentRange(r, 10));
        assertEquals("bytes */10", RangeHeaderParser.unsatisfiableContentRange(10));
    }

    @Test
    void parserNeverThrowsOnHostileInput() {
        String[] hostile = {"bytes=-", "bytes=--", "bytes=9999999999999999999999-1",
                "bytes=0-9999999999999999999999", "=", "bytes=0-", "bytes=-x", "   =   ",
                "bytes=0-10;complex=+foo"};
        for (String value : hostile) {
            RangeHeaderParser.Result r = RangeHeaderParser.parse(value, 100);
            assertNotNull(r.disposition(), "disposition for: " + value);
        }
    }

    @Test
    void windowBoundsAreNonNegativeForSatisfiedResults() {
        RangeHeaderParser.Result r = RangeHeaderParser.parse("bytes=0-0", 100);
        assertEquals(RangeHeaderParser.Disposition.SATISFIED, r.disposition());
        assertEquals(0, r.start());
        assertEquals(0, r.endInclusive());
        assertEquals(1, r.length());
    }
}
