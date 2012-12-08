package com.shadworld.poolserver.source.daemonhandler;

import java.io.IOException;

import org.json.JSONException;

import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.PSJExchange;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.WorkSource;

final class BlockCheckExchange extends PSJExchange {
	/**
	 * 
	 */
	private final BlockChainTracker blockChainTracker;
	private WorkSource source;
	private final boolean isNativeLongpollOriginated;

	BlockCheckExchange(BlockChainTracker blockChainTracker, WorkSource source, boolean isNativeLongpollOriginated) {
		this.blockChainTracker = blockChainTracker;
		this.source = source;
		this.isNativeLongpollOriginated = isNativeLongpollOriginated;
	}
	
	public void reset(WorkSource source) {
		super.reset(source);
		this.source = source;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.shadworld.poolserver.PSJExchange#onResponseComplete
	 * ()
	 */
	@Override
	public void onResponseComplete() throws IOException {
		super.onResponseComplete();
		String s = getResponseContent();
		if (s != null) {
			if (getResponseStatus() == 200) {
				try {
					JsonRpcResponse response = new JsonRpcResponse(s, null);
					long result = response.toJSONObject().optLong("result", -1);
					if (isNativeLongpollOriginated && Res.isTrace()) {
						Res.logTrace(Res.TRACE_BLOCKMON, "Received verification for native longpoll.  Blocknum: " + result);
					}
					blockChainTracker.reportBlockNum(result, source);
				} catch (JSONException e) {
					Res.logError(s, e);
				}
			} else {
				Res.logError("getblocknumber response status: " + getResponseStatus()
						+ " for url: " + source.getUrl());
				if (Res.isDebug())
					Res.logError(s);
			}
		}
	}
}