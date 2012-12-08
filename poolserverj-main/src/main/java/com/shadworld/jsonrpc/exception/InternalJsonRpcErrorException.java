package com.shadworld.jsonrpc.exception;

import org.json.JSONObject;

public class InternalJsonRpcErrorException extends JsonRpcResponseException {

	public InternalJsonRpcErrorException() {
		// TODO Auto-generated constructor stub
	}

	public InternalJsonRpcErrorException(String message, Throwable cause) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}

	public InternalJsonRpcErrorException(String message, Throwable cause, JSONObject data) {
		super(message, cause, data);
		// TODO Auto-generated constructor stub
	}

	public InternalJsonRpcErrorException(String message) {
		super(message);
		// TODO Auto-generated constructor stub
	}

	public InternalJsonRpcErrorException(String message, JSONObject data) {
		super(message, data);
		// TODO Auto-generated constructor stub
	}

	public InternalJsonRpcErrorException(Throwable cause) {
		super(cause);
		// TODO Auto-generated constructor stub
	}

	public InternalJsonRpcErrorException(Throwable cause, JSONObject data) {
		super(cause, data);
		// TODO Auto-generated constructor stub
	}

	@Override
	public int getCode() {
		// TODO Auto-generated method stub
		return -32603;
	}

	@Override
	public String getMessageForResponse() {
		// TODO Auto-generated method stub
		return "Internal error.";
	}

}
