package com.shadworld.jsonrpc.exception;

import org.json.JSONObject;

import com.shadworld.exception.ShadException;

public class JsonRpcResponseException extends ShadException {
	
	JSONObject data;
	int code;
	String message;
	
	public JsonRpcResponseException(int code, String message, JSONObject data) {
		this.data = data;
		this.code = code;
		this.message = message;
	}
	
	public JsonRpcResponseException(int code, String message) {
		this(code, message, null);
	}


	public JsonRpcResponseException() {
		// TODO Auto-generated constructor stub
	}

	public JsonRpcResponseException(String message, Throwable cause) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}
	
	public JsonRpcResponseException(String message, Throwable cause, JSONObject data) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}

	public JsonRpcResponseException(String message) {
		super(message);
		// TODO Auto-generated constructor stub
	}
	
	public JsonRpcResponseException(String message, JSONObject data) {
		super(message);
		// TODO Auto-generated constructor stub
	}

	public JsonRpcResponseException(Throwable cause) {
		super(cause);
		// TODO Auto-generated constructor stub
	}
	
	public JsonRpcResponseException(Throwable cause, JSONObject data) {
		super(cause);
		// TODO Auto-generated constructor stub
	}
	
	public int getCode() {
		return code;
	}
	
	public String getMessageForResponse() {
		return message;
	}
	
	public JSONObject getData() {
		return data;
	}

}
