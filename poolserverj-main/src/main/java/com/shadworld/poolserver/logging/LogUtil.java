package com.shadworld.poolserver.logging;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.shadworld.util.Time;
import com.shadworld.utils.L;

public class LogUtil {

	/**
	 * return value for bool dependant on whether pushpool compatible format is requested.
	 * @param bool
	 * @param ppNullValue value to return for null if pushpool mode
	 * @return
	 */
	public static String getCompatibleValue(String string, String ppNullValue, String nullString, boolean usePushPoolCompatibleFormat) {
		if (string == null)
			return usePushPoolCompatibleFormat ? ppNullValue : nullString;
		return string;
	}
	
	/**
	 * return value for bool dependant on whether pushpool compatible format is requested.
	 * @param bool
	 * @param ppNullValue value to return for null if pushpool mode
	 * @return
	 */
	public static String getCompatibleValue(Boolean bool, String ppNullValue, String nullString, boolean usePushPoolCompatibleFormat) {
		if (bool == null)
			return usePushPoolCompatibleFormat ? ppNullValue : nullString;
		return usePushPoolCompatibleFormat ? bool ? "Y" : "N" : bool.toString();
	}
	
	private static SimpleDateFormat pushpoolDateFormat = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS000]");
	
	/**
	 * DONT CALL THIS UNLESS YOU KNOW YOU ARE USING PUSHPOOL COMPAT MODE.  IF NOT YOU SHOULD BE PASSING A java.sql.Time object
	 * @param time
	 * @return
	 */
	public static String getCompatibleTimeValue(long time) {
		return pushpoolDateFormat.format(new Date(time));
	}
	
	public static void main(String[] args) {
		L.println(getCompatibleTimeValue(System.currentTimeMillis()));
	}
	
}
