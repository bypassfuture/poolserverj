package com.shadworld.jsonrpc;

import org.json.JSONException;
import org.json.JSONObject;

import com.shadworld.poolserver.conf.Res;

public class JsonRpcResponse extends AbstractJsonRpcObject {

	
	private JSONObject result;
	
	private String nonceRange;
	
	private String rejectReason;
	
	private JSONObject error;
	private Error wrappedError;
	int id = -1;
	
	
	/**
	 * use this constructor when receiving a response.  Pass the jsonObject if you've got one for performance or just the String;
	 * @param jsonString
	 * @param object
	 * @throws JSONException 
	 */
	public JsonRpcResponse(final String jsonString, final JSONObject object) throws JSONException {
		super(jsonString, object);
	}
	
	public void reset(final String jsonString, final JSONObject object) throws JSONException {
		super.reset(jsonString, object);
		result = null;
		nonceRange = null;
		rejectReason = null;
		error = null;
		wrappedError = null;
		int id = -1;
	}
	
	public Error getError() {
		if (wrappedError == null) {
			if (error == null)
				error = object.optJSONObject("error");
			if (error != null) {
					wrappedError = new Error(error);

			}
		}
		return wrappedError;
	}
	
	public int getId() {
		if (id == -1) {
			if (object != null)
				id = object.optInt("id", -1);
		}
		return id;
	}
	
	public JSONObject getResult() {
		if (result == null) {
			result = object.optJSONObject("result");
		}
		return result;
	}
	
	public JsonRpcResponse(int id) {
		this.id = id;
	}
	
	public void setResult(JSONObject result) {
		clearCache();
		this.result = result;
	}
	
	public void setError(JSONObject error) {
		clearCache();
		this.error = error;
	}
	
	
	

	/**
	 * @return the nonceRange
	 */
	public String getNonceRange() {
		return nonceRange;
	}

	/**
	 * @param nonceRange the nonceRange to set
	 */
	public void setNonceRange(String nonceRange) {
		this.nonceRange = nonceRange;
	}

	/**
	 * @return the rejectReason
	 */
	public String getRejectReason() {
		return rejectReason;
	}

	/**
	 * @param rejectReason the rejectReason to set
	 */
	public void setRejectReason(String rejectReason) {
		this.rejectReason = rejectReason;
	}

	@Override
	public JSONObject toJSONObject() {
		if (object == null) {
			object = new JSONObject();
			try {
				object.put("result", error == null ? result : JSONObject.NULL);
				object.put("error", result == null ? error : JSONObject.NULL);
				object.put("id", id);
			} catch (JSONException e) {
				Res.logException(e);
			}
			
		}
		return object;
	}
	
	public class Error {
		
		private JSONObject error;
		private Integer code;
		private String message;
		private String data;
		
		public Error(JSONObject error) {
			this.error = error;
		}
		
		public void reset(JSONObject error) {
			code = null;
			message = null;
			data = null;
			this.error = error;
			
		}
		
		public int getCode() {
			if (code == null)
				code = error.optInt("code", 0);
			return code;
		}
		
		public String getMessage() {
			if (message == null)
				message = error.optString("message", null);
			return message;
		}
		
		public String getData() {
			if (data == null)
				data = error.optString("data", null);
			return data;
		}
		
		
		
		public String toPlainString() {
			StringBuilder sb = new StringBuilder("code: ");
			sb.append(getCode());
			sb.append(" message: ").append(getMessage());
			if (getData() != null)
				sb.append(" data: ").append(getData());
			return sb.toString();
		}
	}
	

	
}
