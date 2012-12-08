package com.shadworld.jsonrpc;

import org.json.JSONException;
import org.json.JSONObject;

import com.shadworld.poolserver.conf.Res;


public abstract class AbstractJsonRpcObject {

	protected String jsonString;
	protected JSONObject object;
	
	/**
	 * use this constructor when receiving a response.  Pass the jsonObject if you've got one for performance or just the String;
	 * @param jsonString
	 * @param object
	 * @throws JSONException 
	 */
	public AbstractJsonRpcObject(final String jsonString, final JSONObject object) throws JSONException {
		reset(jsonString, object);
	}
	
	public void reset(final String jsonString, final JSONObject object) throws JSONException {
		this.jsonString = jsonString;
		if (object == null)
			this.object = new JSONObject(jsonString);
		else
			this.object = object;
	}
	
	public AbstractJsonRpcObject() {
	}
	
	public String toJSONString() {
		return toJSONString(0);
	}
	
	public String toJSONString(int indentFactor) {
		if (jsonString == null) {
			object = toJSONObject();
			try {
				jsonString = object.toString(indentFactor);
			} catch (JSONException e) {
				Res.logException(e);
				return null;
			}
		}
		return jsonString;
	}
	
	/**
	 * call whenever internal values of object changes to ensure no cached copies are retained;
	 */
	protected void clearCache() {
		object = null;
		jsonString = null;
	}
	
	/**
	 * build JSON object.  Use this for creating a request or response.  For a received request/response you should already have it.
	 * @return
	 */
	public abstract JSONObject toJSONObject();
	
	/**
	 * 
	 * @return JSONObject based on jsonString passed to constructor.  Use this for a received response.
	 * @throws JSONException 
	 */
	public JSONObject getJSONObject() {
		if (object != null)
			return object;
		if (jsonString != null)
			try {
				return new JSONObject(jsonString);
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		return toJSONObject();
	}
	
	public String toString() {
		try {
			return getJSONObject().toString(4);
		} catch (JSONException e) {
			return "Failed to build JSONObject: " + super.toString();
		}
	}
}
