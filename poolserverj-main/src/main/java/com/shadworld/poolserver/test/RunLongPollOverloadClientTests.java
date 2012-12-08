package com.shadworld.poolserver.test;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.json.JSONException;

import com.google.bitcoin.core.PSJBlock;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ProtocolException;
import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.util.Time;
import com.shadworld.utils.L;

public class RunLongPollOverloadClientTests {

	/**
	 * @param args
	 * @throws JSONException 
	 * @throws ProtocolException 
	 */
	public static void main(String[] args) throws JSONException, ProtocolException {
		
		long start = System.currentTimeMillis();
		
		int numPolls = 900;
		
		ConfigureTest.configureTest();
		Res.setDebug(false);
		
		JsonRpcResponse response;
		//verify local daemon is responding
		JsonRpcClient lclient = new JsonRpcClient("http://localhost:8352/", "rpcuser", "rpcpassword");
		//JsonRpcClient lclient = new JsonRpcClient("http://pps.btcguild.com:7332/", "test", "test");
		JsonRpcRequest lrequest = new JsonRpcRequest("getblocknumber", lclient.newRequestId());
		lrequest = new JsonRpcRequest("getworkaux", lclient.newRequestId());
		JsonRpcResponse res = lclient.doRequest(lrequest);
		//L.println(lclient.doRequest(lrequest).toJSONObject().toString(4));
		if (res.getError() != null)
			L.println(res.getError().getMessage());
		else
			L.println(res.toJSONObject().toString(4));
		
		L.println("STARTING LONGPOLL");
		JsonRpcClient lpclient = new JsonRpcClient(true, "http://localhost:8999/LP/", "test-worker-client", "test");
		//JsonRpcClient lpclient = new JsonRpcClient(true, "http://pps.btcguild.com:7332/", "svaljin1", "test");
		//JsonRpcClient lpclient = new JsonRpcClient(true, "http://pps.btcguild.com:7332/", "test", "test");
		QueuedThreadPool lptp = new QueuedThreadPool((int) (numPolls * 1.2));
		lpclient.getClient().setThreadPool(lptp);
		final int[] numReturns = new int[] {0, 0, 0};
		for (int i = 0; i < numPolls; i++) {
			JsonRpcRequest lprequest = new JsonRpcRequest("getwork", lpclient.newRequestId());
			final int x = i;
			final long[] times = new long[]{0,0};
			ContentExchange ex = new ContentExchange() {

				/* (non-Javadoc)
				 * @see org.eclipse.jetty.client.HttpExchange#onResponseComplete()
				 */
				@Override
				protected void onResponseComplete() throws IOException {
					synchronized (numReturns) {
						if (times[0] == 0)
							times[0] = System.currentTimeMillis();
						numReturns[0] = numReturns[0] + 1;
						if (numReturns[1] + numReturns[2] > 890) {
							L.println("delay: " + (System.currentTimeMillis() - times[0]));
							L.println("returns: " + numReturns[0] + " success: " + numReturns[1] + " fails: " + numReturns[2]);
						}
						try {
							new JsonRpcResponse(getResponseContent(), null).getResult().getString("data");
							numReturns[1] = numReturns[1] + 1;
						} catch (Throwable t) {
							numReturns[2] = numReturns[2] + 1;
							Res.logInfo("Failed HTTP response: " + this.getResponseStatus());
						}
						
					}
				}

				/* (non-Javadoc)
				 * @see org.eclipse.jetty.client.HttpExchange#onConnectionFailed(java.lang.Throwable)
				 */
				@Override
				protected void onConnectionFailed(Throwable x) {
					numReturns[0] = numReturns[0] + 1;
					numReturns[2] = numReturns[2] + 1;
					super.onConnectionFailed(x);
				}

				/* (non-Javadoc)
				 * @see org.eclipse.jetty.client.HttpExchange#onException(java.lang.Throwable)
				 */
				@Override
				protected void onException(Throwable x) {
					numReturns[0] = numReturns[0] + 1;
					numReturns[2] = numReturns[2] + 1;
					super.onException(x);
				}

				/* (non-Javadoc)
				 * @see org.eclipse.jetty.client.HttpExchange#onExpire()
				 */
				@Override
				protected void onExpire() {
					numReturns[0] = numReturns[0] + 1;
					numReturns[2] = numReturns[2] + 1;
					super.onExpire();
				}
				
				
				
			};
			try {
				lpclient.doRequest(lprequest, ex);
				L.println("issued: " + i);
		
			} catch (IOException e) {
				L.println(i + ": " + e.getMessage());
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		while (numPolls > numReturns[0])
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		//lptp.stop();
		try {
			lpclient.getClient().stop();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (System.currentTimeMillis() > start + 10000)
			main(args);
//		JsonRpcRequest lprequest = new JsonRpcRequest("getwork", lpclient.newRequestId());
//		response = lpclient.doRequest(lprequest);
//		L.println("LONGPOLL RETURNED");
//		
//		if (response == null) {
//			L.println("LP RESPONSE NULL");
//			L.println(lpclient.getFullRequestString());
//		} else {
//			L.println(lpclient.getResponseHeaderString());
//			L.println(response.toJSONObject().toString(4));
//		}
		
	}
	
	public static void logRequest(JsonRpcClient client, JsonRpcResponse response) {
		L.println(client.getFullExchangeString());
		if (response != null) {
			L.println(response.toJSONString(4));
		}
	}
	
	public static void logRequest(JsonRpcClient client, List<JsonRpcResponse> responses) {
		L.println(client.getFullExchangeString());
		if (responses != null) {
			for (JsonRpcResponse response: responses)
				L.println(response.toJSONString(4));
		}
	}
}
