package com.shadworld.jsonrpc.exception;

import org.json.JSONObject;

public class InvalidJsonRpcRequestException extends JsonRpcResponseException {

	public InvalidJsonRpcRequestException() {
		// TODO Auto-generated constructor stub
	}
	
	public InvalidJsonRpcRequestException(String message, JSONObject data) {
		super(message, data);
		// TODO Auto-generated constructor stub
	}



	public InvalidJsonRpcRequestException(String message, Throwable cause, JSONObject data) {
		super(message, cause, data);
		// TODO Auto-generated constructor stub
	}



	public InvalidJsonRpcRequestException(String message, Throwable cause) {
		super(message, cause);
		// TODO Auto-generated constructor stub
	}



	public InvalidJsonRpcRequestException(String message) {
		super(message);
		// TODO Auto-generated constructor stub
	}



	public InvalidJsonRpcRequestException(Throwable cause, JSONObject data) {
		super(cause, data);
		// TODO Auto-generated constructor stub
	}



	public InvalidJsonRpcRequestException(Throwable cause) {
		super(cause);
		// TODO Auto-generated constructor stub
	}



	@Override
	public int getCode() {
		return -32600;
	}

	@Override
	public String getMessageForResponse() {
		return "Invalid Request.";
	}
	
	

}
