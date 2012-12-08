package com.shadworld.poolserver.notify;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.utils.UrlTools;

public class HttpGetNotifyBlockChangeMethod extends NotifyBlockChangeMethod {

	private String url;
	//private HttpClient getworkClient; //uses shared getworkClient now
	private Thread notifyThread;
	
	private long delay;
	private boolean blockwon;
	private String source;
	
	public HttpGetNotifyBlockChangeMethod(String url) {
		super();
		this.url = url;
//		getworkClient = new HttpClient();
//		getworkClient.setTimeout(30000);
//		getworkClient.setMaxRetries(3);
//		QueuedThreadPool pool = new QueuedThreadPool();
//		getworkClient.setThreadPool(pool);
//		pool.setMinThreads(1);
//        pool.setDaemon(true);
//        pool.setName("HttpClient-notify-block-change");
//		try {
//			getworkClient.start();
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		notifyThread = newNotifyThread();
	}

	private Thread newNotifyThread() {
		return new Thread("notify-block-change") {
			public void run() {
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
				}
				ContentExchange ex = new ContentExchange();
				ex.setMethod("GET");
				String sourceEnc = null;
				try {
					sourceEnc = URLEncoder.encode(source, "UTF-8");
				} catch (UnsupportedEncodingException e) {
				}
				ex.setURL(HttpGetNotifyBlockChangeMethod.this.url + "?blockchange=true&blockwon=" + blockwon + "&source=" + sourceEnc);
				try {
					Res.getSharedClient().getClient().send(ex);
				} catch (IOException e) {
				}
			}
		};
	}


	@Override
	public void notifyBlockChange(final long delay, final boolean blockwon, final String source) {
		this.delay = delay;
		this.blockwon = blockwon;
		this.source = source;
		notifyThread = newNotifyThread();
		notifyThread.start();
	}



	@Override
	public void shutdown() {
//		try {
//			getworkClient.stop();
//		} catch (Exception e) {
//		}
	}

}
