package com.shadworld.jsonrpc.exception;

import org.json.JSONObject;

public class InvalidParamsException extends JsonRpcResponseException {

	public InvalidParamsException() {
		// TODO Auto-generated constructor stub
	}

	public InvalidParamsException(String message, Throwable cause) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}

	public InvalidParamsException(String message, Throwable cause, JSONObject data) {
		super(message, cause, data);
		// TODO Auto-generated constructor stub
	}

	public InvalidParamsException(String message) {
		super(message);
		// TODO Auto-generated constructor stub
	}

	public InvalidParamsException(String message, JSONObject data) {
		super(message, data);
		// TODO Auto-generated constructor stub
	}

	public InvalidParamsException(Throwable cause) {
		super(cause);
		// TODO Auto-generated constructor stub
	}

	public InvalidParamsException(Throwable cause, JSONObject data) {
		super(cause, data);
		// TODO Auto-generated constructor stub
	}

	@Override
	public int getCode() {
		// TODO Auto-generated method stub
		return -32602;
	}

	@Override
	public String getMessageForResponse() {
		// TODO Auto-generated method stub
		return "Invalid params.";
	}

}
