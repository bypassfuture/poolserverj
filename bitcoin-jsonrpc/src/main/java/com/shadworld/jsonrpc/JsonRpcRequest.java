package com.shadworld.jsonrpc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.shadworld.poolserver.conf.Res;

public class JsonRpcRequest extends AbstractJsonRpcObject {

	public static final String METHOD_GETWORK = "getwork";
	public static final String METHOD_GETWORKS = "getworks";
	public static final String METHOD_GETBLOCKNUMBER = "getblocknumber";
	
	public static final String METHOD_GETWORKAUX = "getworkaux";
	public static final String METHOD_GETAUXBLOCK = "getauxblock";
	public static final String METHOD_BUILDMERKLETREE = "buildmerkletree";
	
	long clientHashRate = -1;
	
	String method;
	JSONArray params;
	Integer id;
	String requesterIp;
	String requestUrl;
	String username;
	
	/**
	 * use this constructor when receiving a request.  Pass the jsonObject if you've got one for performance or just the String;
	 * @param jsonString
	 * @param object
	 * @throws JSONException 
	 */
	public JsonRpcRequest(final String jsonString, final JSONObject object) throws JSONException {
		super(jsonString, object);
	}
	
	public void reset(final String jsonString, final JSONObject object) throws JSONException {
		super.reset(jsonString, object);
		clientHashRate = -1;
		method = null;
		params = null;
		id = null;
		requesterIp = null;
		requestUrl = null;
		username = null;
	}
	
	public void reset(final String method, final int id, final Object ... parameters) throws JSONException {
		super.reset(jsonString, object);
		clientHashRate = -1;
		this.method = method;
		this.id = id;
		
		requesterIp = null;
		requestUrl = null;
		username = null;
		
		if (parameters != null && parameters.length > 0) {
			params = new JSONArray();
			for (int i = 0; i < parameters.length; i++)
				params.put(parameters[i]);
		} else {
			params = null;
		}
	}
	
	public JsonRpcRequest(final String method, final int id, final Object ... parameters) {
		this.method = method;
		this.id = id;
		if (parameters != null && parameters.length > 0) {
			params = new JSONArray();
			for (int i = 0; i < parameters.length; i++)
				params.put(parameters[i]);
		}
	}
	
	public Object getId() {
		if (id == null) {
			JSONObject o = getJSONObject();
			if (o == null)
				return null;
				id = o.optInt("id", -1);
		}
		return id;
	}
	
	public String getMethod() {
		if (method == null) {
			JSONObject o = getJSONObject();
			method = o == null ? null : o.optString("method");
		}
		return method;
	}
	
	public JSONArray getParams() {
		if (params == null) {
			JSONObject o = getJSONObject();
			params = o == null ? null : o.optJSONArray("params");
		}
		return params;
	}
	
	

	/**
	 * only used on the server side for received requests.
	 * @return the requesterIp
	 */
	public String getRequesterIp() {
		return requesterIp;
	}

	/**
	 * only used on the server side for received requests.
	 * @param requesterIp the requesterIp to set
	 */
	public void setRequesterIp(String requesterIp) {
		this.requesterIp = requesterIp;
	}
	
	/**
	 * only used on the server side for received requests.
	 * @return the requestUrl
	 */
	public String getRequestUrl() {
		return requestUrl;
	}

	/**
	 * only used on the server side for received requests.
	 * @param requestUrl the requestUrl to set
	 */
	public void setRequestUrl(String requestUrl) {
		this.requestUrl = requestUrl;
	}

	/**
	* only used on the server side for received requests.
	  * @return the username
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * only used on the server side for received requests.
	 * @param username the username to set
	 */
	public void setUsername(String username) {
		this.username = username;
	}
	
	

	/**
	 * This is only set if client has sent an X-Mining-Hashrate header
	 * && X-Mining-Extensions includes noncerange as a supported extension.
	 * it indicates hashrate miner is capable of and should be used to calculate noncerange
	 * @return the clientHashRate
	 */
	public long getClientHashRate() {
		return clientHashRate;
	}

	/**
	 * This is only set if client has sent an X-Mining-Hashrate header
	 * && X-Mining-Extensions includes noncerange as a supported extension.
	 * it indicates hashrate miner is capable of and should be used to calculate noncerange
	 * @param clientHashRate the clientHashRate to set
	 */
	public void setClientHashRate(long hashRate) {
		this.clientHashRate = hashRate;
	}

	@Override
	public JSONObject toJSONObject() {
		if (object == null) {
			object = new JSONObject();
			try {
				object.put("method", method);
				if (params != null && params.length() > 0)
					object.put("params", params);
				object.put("id", getId());
			} catch (JSONException e) {
				Res.logException(e);
			}
		}
		return object;
	}
	
}
