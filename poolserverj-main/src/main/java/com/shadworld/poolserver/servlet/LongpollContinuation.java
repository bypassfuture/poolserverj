package com.shadworld.poolserver.servlet;

import java.util.concurrent.ScheduledFuture;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.server.HttpConnection;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.entity.Worker;

public class LongpollContinuation {
	public Continuation continuation;
	public JsonRpcRequest request;
	public Worker worker;
	
	public ScheduledFuture<Boolean> expireTask;
	public boolean complete;
	
	public HttpConnection http;
}