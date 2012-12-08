package com.shadworld.poolserver.logging;

import com.shadworld.jsonrpc.JsonRpcRequest;

public class RequestEntry {
	final JsonRpcRequest request;
	final long submitTime;
	
	public RequestEntry(JsonRpcRequest request, long submitTime) {
		super();
		this.request = request;
		this.submitTime = submitTime;
	}

	/**
	 * @return the request
	 */
	public JsonRpcRequest getRequest() {
		return request;
	}

	/**
	 * @return the submitTime
	 */
	public long getSubmitTime() {
		return submitTime;
	}
	
	
	
	
}