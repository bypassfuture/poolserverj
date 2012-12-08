package com.shadworld.poolserver.source;

import java.util.HashSet;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.WorkProxy;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.daemonhandler.JsonRpcDaemonHandler;

public class DaemonSource extends WorkSource {

	private boolean allowJsonRpcPassThru = false;
	
	private boolean rewriteDifficulty = true;
	private String rewriteDifficultyTarget = Res.getEasyDifficultyTargetAsString();
	
	final private HashSet<String> allowedPassThruMethods = new HashSet();
	
	public DaemonSource(WorkProxy proxy, String name, String url, String username, String password, int weighting) {
		super(proxy, name, url, username, password, weighting);
	}
	
	/**
	 * run a non-cached method on upstream server and return result.
	 * @param request
	 * @return
	 */
	public JsonRpcResponse doProxyRequest(JsonRpcRequest request) {
		return handler.getClient().doRequest(request);
	}


	@Override
	public boolean isSupportUpstreamLongPoll() {
		return false;
	}

	@Override
	public boolean isBitcoinDaemon() {
		return true;
	}

	/**
	 * @return the allowJsonRpcPassThru
	 */
	public boolean isAllowJsonRpcPassThru() {
		return allowJsonRpcPassThru;
	}

	/**
	 * @param allowJsonRpcPassThru the allowJsonRpcPassThru to set
	 */
	public void setAllowJsonRpcPassThru(boolean allowJsonRpcPassThru) {
		this.allowJsonRpcPassThru = allowJsonRpcPassThru;
	}

	/**
	 * @return the allowedPassThruMethods
	 */
	public HashSet<String> getAllowedPassThruMethods() {
		return allowedPassThruMethods;
	}

//	/**
//	 * @param allowedPassThruMethods the allowedPassThruMethods to set
//	 */
//	public void setAllowedPassThruMethods(HashSet<String> allowedPassThruMethods) {
//		this.allowedPassThruMethods = allowedPassThruMethods;
//	}

	/**
	 * @param rewriteDifficulty the rewriteDifficulty to set
	 */
	public void setRewriteDifficulty(boolean rewriteDifficulty) {
		this.rewriteDifficulty = rewriteDifficulty;
	}

	/**
	 * @param rewriteDifficultyTarget the rewriteDifficultyTarget to set
	 */
	public void setRewriteDifficultyTarget(String rewriteDifficultyTarget) {
		this.rewriteDifficultyTarget = rewriteDifficultyTarget;
	}

	@Override
	public boolean isRewriteDifficulty() {
		return rewriteDifficulty;
	}

	@Override
	public String getRewriteDifficultyTarget() {
		// TODO Auto-generated method stub
		return rewriteDifficultyTarget;
	}

	
	
}
