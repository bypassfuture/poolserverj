package com.shadworld.jsonrpc.exception;

public class InvalidWorkException extends JsonRpcResponseException {

	@Override
	public int getCode() {
		// TODO Auto-generated method stub
		return -29;
	}

	@Override
	public String getMessageForResponse() {
		// TODO Auto-generated method stub
		return "invalid-work";
	}

}
