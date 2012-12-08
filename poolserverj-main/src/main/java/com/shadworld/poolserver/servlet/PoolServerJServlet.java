package com.shadworld.poolserver.servlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.exception.InvalidJsonRpcRequestException;
import com.shadworld.jsonrpc.exception.JsonRpcResponseException;
import com.shadworld.jsonrpc.exception.MethodNotFoundException;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.entity.Worker;

public class PoolServerJServlet extends AbstractJsonRpcServlet {

	public PoolServerJServlet(PoolServer poolServer, String longPollUrl, String rollNTimeString) {
		super(poolServer, longPollUrl, rollNTimeString);
	}

	@Override
	protected JsonRpcResponse getResponse(JsonRpcRequest request, HttpServletRequest servReq
			, HttpServletResponse resp, Worker worker)
			throws JsonRpcResponseException {
		
		return workProxy.handleRequest(request, worker);
		
	} 

}
