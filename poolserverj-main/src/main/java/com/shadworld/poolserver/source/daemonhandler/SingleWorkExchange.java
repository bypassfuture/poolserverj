package com.shadworld.poolserver.source.daemonhandler;

import java.io.IOException;

import com.shadworld.poolserver.source.GetWorkRequest;
import com.shadworld.poolserver.source.WorkFetcherThread;
import com.shadworld.poolserver.source.WorkSource;

public final class SingleWorkExchange extends WorkExchange {
	/**
	 * 
	 */

	public SingleWorkExchange(WorkSource workSource, DaemonHandler handler, GetWorkRequest workRequest) {
		super(workSource, handler, workRequest);
		workRequest.setNumRequested(1);
	}
	
	public void reset(WorkSource newOwner) {
		super.reset(newOwner, 1);
		//setDoneNotify(false);
	}

	public void onResponseComplete() throws IOException {
		try {
			super.onResponseComplete();

			if (getResponseStatus() != 200) {
				workSourceOwner.handleBadHttpResponse(this);
				// return;
			} else {
				workSourceOwner.getState().registerHttpSuccess();
			}
			String responseString = this.getResponseContent();
			handler.processSingleResponse(responseString, workRequest.getReportedBlock(), 1);
			handler.getFetcherThread().notifyQueue(workRequest, true);
		} catch (Exception e) {
			handler.getFetcherThread().notifyQueue(workRequest, false);
			throw new RuntimeException(e);
		}
	}
}