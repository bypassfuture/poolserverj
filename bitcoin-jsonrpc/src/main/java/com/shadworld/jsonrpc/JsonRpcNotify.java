package com.shadworld.jsonrpc;

import org.json.JSONException;
import org.json.JSONObject;

public class JsonRpcNotify extends JsonRpcRequest {

	/**
	 * use this constructor when receiving a notify.  Pass the jsonObject if you've got one for performance or just the String;
	 * @param jsonString
	 * @param object
	 * @throws JSONException 
	 */
	public JsonRpcNotify(String jsonString, JSONObject object) throws JSONException {
		super(jsonString, object);
	}
	
	public JsonRpcNotify(String method, Object[] parameters) {
		super(method, -1, parameters);
		// TODO Auto-generated constructor stub
	}

	public Object getId() {
		return JSONObject.NULL;
	}
}
