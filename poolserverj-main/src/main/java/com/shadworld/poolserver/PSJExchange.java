package com.shadworld.poolserver;

import java.io.IOException;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Buffer;

import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.logging.ShareSubmitter;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;

public class PSJExchange extends ContentExchange {
	
	//HttpFields responseHeaders;

	public WorkSource workSourceOwner;
	public ShareSubmitter submitterOwner;
	long startTime = System.currentTimeMillis();

	
	public PSJExchange() {
		super(true);
		startTime = System.currentTimeMillis();
	}

	public PSJExchange(boolean cacheFields) {
		super(cacheFields);
		// TODO Auto-generated constructor stub
	}
	
	public void reset(WorkSource newOwner) {
		super.reset();
		workSourceOwner = null;
		//submitterOwner = null; there is only one so it can't change
		startTime = System.currentTimeMillis();
	}
	
	public void onResponseComplete() throws IOException {
		super.onResponseComplete();
		Stats.get().registerHttpReponseComplete(this, System.currentTimeMillis() - startTime);
	}

	@Override
	protected void onResponseHeaderComplete() throws IOException {
		super.onResponseHeaderComplete();
		Stats.get().registerHttpReponseHeaderComplete(this, System.currentTimeMillis() - startTime);
	}
	
	@Override
	protected synchronized void onRetry() throws IOException {
		Res.logDebug("RETRY");
		Stats.get().registerHttpRetry(this, System.currentTimeMillis() - startTime);
	}

	@Override
	protected void onConnectionFailed(Throwable x) {
		//Res.logDebug("CON_FAIL");
		Stats.get().registerHttpConnectionFail(this, x, System.currentTimeMillis() - startTime);
	}

	@Override
	protected void onException(Throwable x) {
		Res.logError("Exchange Exception from: " + getClass().getName(), x);
		Stats.get().registerHttpException(this, x, System.currentTimeMillis() - startTime);
	}

	@Override
	protected void onExpire() {
		//Res.logDebug("EXPIRE");
		Stats.get().registerHttpExpire(this, System.currentTimeMillis() - startTime);
	}

}
