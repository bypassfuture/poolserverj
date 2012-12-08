package com.shadworld.jsonrpc;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.eclipse.jetty.client.ContentExchange;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class JsonRequestExchange extends ContentExchange {

	private String responseContent;
	
	/* (non-Javadoc)
	 * @see org.eclipse.jetty.client.HttpExchange#onResponseComplete()
	 */
	@Override
	protected void onResponseComplete() throws IOException {
		super.onResponseComplete();
	}

	/**
	 * 
	 * @return a JSONObject constructed from the response or null if the request didn't return a valid JSON string.
	 */
	public JSONObject getJSONObject() {
		try {
			return new JSONObject(getResponseContent());
		} catch (JSONException e) {
			return null;
		}
	}
	
	/**
	 * 
	 * @return a JSONArray constructed from the response or null if the request didn't return a valid JSON array string.
	 */
	public JSONArray getJSONArray() {
		try {
			return new JSONArray(getResponseContent());
		} catch (Exception e) {
			return null;
		} 
	}
	
	public boolean isJSONArray() {
		return JsonUtil.isJSONArray(getResponseContent());
	}
	
	public String getResponseContent() {
		try {
			if (responseContent == null)
				responseContent = super.getResponseContent();
		} catch (UnsupportedEncodingException e) {	
		}
		return responseContent;
	}
	
}
