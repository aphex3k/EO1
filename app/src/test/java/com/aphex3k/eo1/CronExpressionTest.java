package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Covers the 5-field cron subset: field grammar, rejects, day-of-week 7→0, and the classic
 * Vixie OR rule when both day-of-month and day-of-week are restricted.
 *
 * <p>Date anchors: 2026-01-01 is a Thursday, so 2026-01-04 is Sunday and 2026-01-05 Monday.
 */
public class CronExpressionTest {

    private static Calendar at(int year, int month0, int dom, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(year, month0, dom, hour, minute, 0);
        return c;
    }

    @Test
    public void parse_fullExpressionIsKept() {
        CronExpression e = CronExpression.parse("0 22-23,0-6 * * *");
        assertNotNull(e);
        assertEquals("0 22-23,0-6 * * *", e.raw());
        assertTrue(e.isMinuteZeroOnly());
        assertTrue(Arrays.equals(new int[]{0, 1, 2, 3, 4, 5, 6, 22, 23}, e.matchedHours()));
        assertTrue(e.isDomUnrestricted());
        assertTrue(e.isMonthUnrestricted());
        assertTrue(e.isDowUnrestricted());
    }

    @Test
    public void parse_trimsWhitespace() {
        CronExpression e = CronExpression.parse("  0 9 * * *  ");
        assertNotNull(e);
        assertEquals("0 9 * * *", e.raw());
    }

    @Test
    public void parse_allStarsMatchesAnyMoment() {
        CronExpression e = CronExpression.parse("* * * * *");
        assertNotNull(e);
        assertTrue(e.matches(at(2026, 0, 4, 23, 59)));
        assertTrue(e.matches(at(2026, 11, 31, 0, 0)));
    }

    @Test
    public void parse_rejectsMalformedExpressions() {
        assertNull(CronExpression.parse(null));
        assertNull(CronExpression.parse("   "));
        assertNull(CronExpression.parse("0 9 * *"));       // 4 fields
        assertNull(CronExpression.parse("0 9 * * * *"));   // 6 fields
        assertNull(CronExpression.parse("60 * * * *"));   // minute out of range
        assertNull(CronExpression.parse("* 24 * * *"));   // hour out of range
        assertNull(CronExpression.parse("* * 0 * *"));    // dom 0 invalid
        assertNull(CronExpression.parse("* * 32 * *"));   // dom out of range
        assertNull(CronExpression.parse("* * * 0 *"));    // month 0 invalid
        assertNull(CronExpression.parse("* * * 13 *"));   // month out of range
        assertNull(CronExpression.parse("* * * * 8"));    // dow out of range
        assertNull(CronExpression.parse("5/15 * * * *")); // step on a single value
        assertNull(CronExpression.parse("*/0 * * * *"));  // zero step
        assertNull(CronExpression.parse("a * * * *"));    // non-numeric
        assertNull(CronExpression.parse("0 5-4 * * *"));  // inverted range
        assertNull(CronExpression.parse("0 5- * * *"));   // open range end
        assertNull(CronExpression.parse("0 ,5 * * *"));   // empty list item
    }

    @Test
    public void dow_7And0BothMeanSunday() {
        Calendar sunday = at(2026, 0, 4, 9, 0);
        Calendar monday = at(2026, 0, 5, 9, 0);
        CronExpression seven = CronExpression.parse("0 9 * * 7");
        CronExpression zero = CronExpression.parse("0 9 * * 0");
        assertNotNull(seven);
        assertNotNull(zero);
        assertTrue(seven.matches(sunday));
        assertTrue(zero.matches(sunday));
        assertFalse(seven.matches(monday));
        assertFalse(zero.matches(monday));
    }

    @Test
    public void vixieRule_bothDayFieldsRestrictedMatchesEither() {
        // 1st of the month OR Monday: 2026-01-01 (Thu, dom=1) and 2026-01-05 (Mon) match,
        // 2026-01-06 (Tue) does not.
        CronExpression e = CronExpression.parse("0 9 1 * 1");
        assertNotNull(e);
        assertTrue(e.matches(at(2026, 0, 1, 9, 0)));
        assertTrue(e.matches(at(2026, 0, 5, 9, 0)));
        assertFalse(e.matches(at(2026, 0, 6, 9, 0)));
    }

    @Test
    public void domRestrictionAloneControlsDay() {
        CronExpression e = CronExpression.parse("0 9 6 * *");
        assertNotNull(e);
        assertTrue(e.matches(at(2026, 0, 6, 9, 0)));  // Tuesday the 6th
        assertFalse(e.matches(at(2026, 0, 5, 9, 0))); // Monday the 5th
    }

    @Test
    public void dowRestrictionAloneControlsDay() {
        CronExpression e = CronExpression.parse("0 9 * * 2"); // Tuesday
        assertNotNull(e);
        assertTrue(e.matches(at(2026, 0, 6, 9, 0)));
        assertFalse(e.matches(at(2026, 0, 5, 9, 0)));
    }

    @Test
    public void monthRestriction() {
        CronExpression e = CronExpression.parse("0 9 * 1 *");
        assertNotNull(e);
        assertTrue(e.matches(at(2026, 0, 4, 9, 0)));
        assertFalse(e.matches(at(2026, 1, 4, 9, 0)));
    }

    @Test
    public void minuteStepAndLists() {
        CronExpression step = CronExpression.parse("*/15 * * * *");
        assertNotNull(step);
        assertTrue(step.matches(at(2026, 0, 4, 3, 0)));
        assertTrue(step.matches(at(2026, 0, 4, 3, 45)));
        assertFalse(step.matches(at(2026, 0, 4, 3, 10)));

        CronExpression list = CronExpression.parse("0 8,13,18 * * *");
        assertNotNull(list);
        assertTrue(list.matches(at(2026, 0, 4, 13, 0)));
        assertFalse(list.matches(at(2026, 0, 4, 9, 0)));
    }

    @Test
    public void hourRangeStep() {
        CronExpression e = CronExpression.parse("0 0-23/6 * * *");
        assertNotNull(e);
        assertTrue(e.matches(at(2026, 0, 4, 12, 0)));
        assertFalse(e.matches(at(2026, 0, 4, 3, 0)));
    }

    @Test
    public void mirrorHelpers_fullDayAndMinuteWindows() {
        CronExpression allDay = CronExpression.parse("0 * * * *");
        assertNotNull(allDay);
        assertTrue(allDay.isMinuteZeroOnly());
        assertEquals(24, allDay.matchedHours().length);

        CronExpression halfHour = CronExpression.parse("30 22-23,0-6 * * *");
        assertNotNull(halfHour);
        assertFalse(halfHour.isMinuteZeroOnly());
    }
}
