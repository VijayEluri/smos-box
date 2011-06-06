package org.esa.beam.dataio.smos.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

public class DateTimeUtils {

    private static final TimeZone TIME_ZONE_UTC = TimeZone.getTimeZone("UTC");

    private static final SimpleDateFormat VARIABLE_HEADER_FORMAT = new SimpleDateFormat("'UTC='yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final SimpleDateFormat FIXED_HEADER_FORMAT = new SimpleDateFormat("'UTC='yyyy-MM-dd'T'HH:mm:ss");
    private static final SimpleDateFormat FILE_NAME_FORMAT = new SimpleDateFormat("yyyyMMdd'T'HHmmss");

    static {
        VARIABLE_HEADER_FORMAT.setTimeZone(TIME_ZONE_UTC);
        FIXED_HEADER_FORMAT.setTimeZone(TIME_ZONE_UTC);
        FILE_NAME_FORMAT.setTimeZone(TIME_ZONE_UTC);
    }

    public static Date cfiDateToUtc(int days, long seconds, long microseconds) {
        final Calendar calendar = new GregorianCalendar(TIME_ZONE_UTC);

        calendar.set(Calendar.YEAR, 2000);
        calendar.set(Calendar.MONTH, 0);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        calendar.add(Calendar.DATE, days);
        calendar.add(Calendar.SECOND, (int) seconds);
        calendar.add(Calendar.MILLISECOND, (int) (microseconds * 0.001));

        return calendar.getTime();
    }

    public static Date mjdFloatDateToUtc(float mjd) {
        final int days = (int) mjd;
        final double secondsFraction = (mjd - days) * 86400.0;
        final int seconds = (int) secondsFraction;
        final int microseconds = (int) ((secondsFraction - seconds) * 1.0e6);

        return cfiDateToUtc(days, seconds, microseconds);
    }

    public static String toVariableHeaderFormat(Date date) {
        return VARIABLE_HEADER_FORMAT.format(date);
    }

    public static String toFixedHeaderFormat(Date date) {
        return FIXED_HEADER_FORMAT.format(date);
    }

    public static String toFileNameFormat(Date date) {
        return FILE_NAME_FORMAT.format(date);
    }
}