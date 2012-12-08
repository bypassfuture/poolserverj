package com.shadworld.jsonrpc;

public class JsonUtil {

	/**
	 * loose hint if the JSON string represents a JSONArray.  Scrolls through initial characters ignoring whitespace AND '{' until it either finds a '[' or another character.  Any other non-whitespace char except for '[' will return false.
	 * @param json
	 * @return
	 */
	public static boolean isJSONArray(String json) {
		if (json == null)
			return false;
		int i = 0;
		char c;
		while (i < json.length()) {
			c = json.charAt(i++);
			if (c == '[')
				return true;
			if (Character.isWhitespace(c) || c == '{')
				continue;
			return false;
		}
		return false;
	}
	
	/**
	 * loose hint if the JSON string represents a JSONObject  Scrolls through initial characters ignoring whitespace AND '{' until it either finds a '{' or another character.  Any other non-whitespace char except for '{' will return false.
	 * @param json
	 * @return
	 */
	public static boolean isJSONObject(String json) {
		if (json == null)
			return false;
		int i = 0;
		char c;
		while (i < json.length()) {
			c = json.charAt(i++);
			if (c == '{')
				return true;
			if (Character.isWhitespace(c))
				continue;
			return false;
		}
		return false;
	}
	
}
