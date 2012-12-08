package com.shadworld.jsonrpc.exception;

import org.json.JSONObject;

public class MethodNotFoundException extends JsonRpcResponseException {

	public MethodNotFoundException() {
		// TODO Auto-generated constructor stub
	}

	public MethodNotFoundException(String message, Throwable cause) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}

	public MethodNotFoundException(String message, Throwable cause, JSONObject data) {
		super(message, cause, data);
		// TODO Auto-generated constructor stub
	}

	public MethodNotFoundException(String message) {
		super(message);
		// TODO Auto-generated constructor stub
	}

	public MethodNotFoundException(String message, JSONObject data) {
		super(message, data);
		// TODO Auto-generated constructor stub
	}

	public MethodNotFoundException(Throwable cause) {
		super(cause);
		// TODO Auto-generated constructor stub
	}

	public MethodNotFoundException(Throwable cause, JSONObject data) {
		super(cause, data);
		// TODO Auto-generated constructor stub
	}

	@Override
	public int getCode() {
		return -32601;
	}

	@Override
	public String getMessageForResponse() {
		return "Method not found.";
	}

}
