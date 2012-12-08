package com.shadworld.poolserver.source.daemonhandler;

import java.io.IOException;

import org.eclipse.jetty.http.HttpFields;

import com.shadworld.poolserver.PSJExchange;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.GetWorkRequest;
import com.shadworld.poolserver.source.WorkFetcherThread;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;

public abstract class WorkExchange extends PSJExchange {

	//int numRequested;
	
	protected GetWorkRequest workRequest;
	
	/**
	 * 
	 */
	//protected final WorkSource workSourceOwner;

	final DaemonHandler handler;
	
	//private boolean doneNotify = false;
	
	//private long startTime;
	//Long reportedBlock;
	public boolean isLongpoll = false;

	public WorkExchange(WorkSource workSource, DaemonHandler handler, GetWorkRequest workRequest) {
		super();
		//setStartTime(System.currentTimeMillis());
		this.workRequest = workRequest;
		workRequest.setStartTime(System.currentTimeMillis());
		
		this.workSourceOwner = workSource;
		this.handler = handler;
		//this.workSourceOwner = this.workSourceOwner;
		//this.numRequested = numRequested;
	}
	
	public void reset(WorkSource newOwner, int numRequested) {
		super.reset(newOwner);
		//setDoneNotify(false);
		//this.numRequested = numRequested;
		
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jetty.client.HttpExchange#onResponseHeaderComplete()
	 */
	@Override
	protected void onResponseHeaderComplete() throws IOException {
		super.onResponseHeaderComplete();
		if (getResponseStatus() == 200) {
			HttpFields fields = getResponseFields();
			if (!this.workSourceOwner.isLongPollCheckDone()) {
				this.workSourceOwner.setLongPollCheckDone(true);

				if (fields != null && fields.containsKey("X-Long-Polling")) {
					this.workSourceOwner.setLongPollUrl(getResponseFields().getStringField("X-Long-Polling"));
					Res.logInfo("Found longpoll header for source" + this.workSourceOwner.getName() + ": " + this.workSourceOwner.getLongPollUrl());
					this.workSourceOwner.startLongpollThread();
				}
			}
			if (fields != null) {
				workRequest.setReportedBlock(fields.getLongField("X-Blocknum"));
				if (workRequest.getReportedBlock() != -1) {
					if (isLongpoll) {
						this.workSourceOwner.getBlockTracker().reportLongpollResponse(workRequest.getReportedBlock(), this.workSourceOwner);
					} else {
						this.workSourceOwner.getBlockTracker().reportBlockNum(workRequest.getReportedBlock(), this.workSourceOwner);
					}
					if (this.workSourceOwner.getMyCurrentBlock() < workRequest.getReportedBlock())
						this.workSourceOwner.setMyCurrentBlock(workRequest.getReportedBlock());
				}
			}
		}
	}

	@Override
	protected void onConnectionFailed(Throwable x) {
		workSourceOwner.handleConnectionFail(this, x);
		handler.getFetcherThread().notifyQueue(workRequest, false);
		workSourceOwner.getState().registerConnectFail();
		Stats.get().registerUpstreamRequestFailed(workSourceOwner);
		super.onConnectionFailed(x);
	}

	@Override
	protected void onException(Throwable x) {
		handler.getFetcherThread().notifyQueue(workRequest, false);
		workSourceOwner.getState().registerConnectFail();
		Stats.get().registerUpstreamRequestFailed(workSourceOwner);
		super.onException(x);
	}

	@Override
	protected void onExpire() {
		handler.getFetcherThread().notifyQueue(workRequest, false);
		workSourceOwner.getState().registerConnectFail();
		Stats.get().registerUpstreamRequestFailed(workSourceOwner);
		super.onExpire();
	}

//	public boolean isDoneNotify() {
//		return doneNotify;
//	}
//
//	public void setDoneNotify(boolean doneNotify) {
//		this.doneNotify = doneNotify;
//	}
//
//	public long getStartTime() {
//		return startTime;
//	}
//
//	public void setStartTime(long startTime) {
//		this.startTime = startTime;
//	}

	
}