package com.shadworld.poolserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.utils.StringTools;

public class NativeLongpollListener extends Thread {

	ScheduledThreadPoolExecutor exec = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2,
			new ThreadFactory() {

				int count = 0;

				@Override
				public Thread newThread(Runnable r) {
					return new Thread(r, "native-longpoll-handler-" + count++);
				}

			});

	ServerSocket srv;
	private int port;
	private int timeout;
	private HashMap<String, WorkSource> sources = new HashMap();
	private HashSet<String> acceptedHosts = new HashSet();
	private boolean shutdown = false;

	private BlockChainTracker blockTracker;

	public NativeLongpollListener(BlockChainTracker blockTracker, int port, int timeout) {
		super("native-longpoll-listener");
		this.blockTracker = blockTracker;
		this.port = port;
		this.timeout = timeout;
	}
	
	public void shutdown() {
		shutdown = true;
	}

	public void run() {
		try {
			srv = new ServerSocket(port, sources.size() + 1);
			//srv.bind(null);
		} catch (IOException e) {
			Res.logError("Failed to start native longpoll listner", e);
			System.exit(1);
		}
		while (!shutdown) {
			try {
				final Socket client = srv.accept();
				InetSocketAddress addr = (InetSocketAddress) client.getRemoteSocketAddress();
				String canonicalAddr = addr.getAddress().getCanonicalHostName();
				if (Res.isTrace()) {
					Res.logTrace(Res.TRACE_BLOCKMON, "Received native longpoll signal from " + addr.getAddress().getHostAddress() + " type: " + addr.getAddress().getClass().getSimpleName() + " canonical: " + canonicalAddr + " toString: " + addr.getAddress());
				}
				if (!acceptedHosts.contains(canonicalAddr)) {
					client.close();
					if (Res.isTrace()) {
						Res.logTrace(Res.TRACE_BLOCKMON, "Native longpoll signal sender not in accepted address list " + canonicalAddr + " - " + acceptedHosts);
					}
					continue;
				}
				final NativeLongpollClientHandler handler = new NativeLongpollClientHandler(client, addr, canonicalAddr);
				exec.submit(handler);

				Runnable timeoutTask = new Runnable() {
					public void run() {
						handler.timedout = true;
						try {
							client.close();
						} catch (IOException e) {
						}
					}
				};
				handler.timeoutFuture = exec.schedule(timeoutTask, timeout, TimeUnit.MILLISECONDS);

			} catch (IOException e) {
				Res.logError("longpoll listener error accepting getworkClient connection.", e);
				continue;
			} catch (Exception e) {
				Res.logError("unknown exception in native longpoll handler", e);
			}
		}
		exec.shutdownNow();
		try {
			srv.close();
		} catch (IOException e) {
			Res.logException(e);
		}
	}

	public void registerWorkSource(WorkSource source) {
		if (source.isNativeLongpoll()) {
			if (sources.put(source.getName(), source) == null) {
				acceptedHosts.addAll(source.getNativeLongpollAllowedHosts());
				return;
			}
			throw new IllegalArgumentException("Source name: " + source.getName()
					+ " is not unique.  Must have unique source names to use native longpolling.");
		}
		throw new IllegalArgumentException("Cannot register: " + source.getName() + " for native longpolling.");
	}

	private class NativeLongpollClientHandler implements Runnable {

		Socket client;
		InputStream in;
		BufferedReader reader;
		InetSocketAddress addr;
		String canonicalAddr;
		boolean timedout = false;

		ScheduledFuture timeoutFuture;

		public NativeLongpollClientHandler(Socket client, InetSocketAddress addr, String canonicalAddr) {
			super();
			this.client = client;
			this.addr = addr;
			this.canonicalAddr = canonicalAddr;
		}

		@Override
		public void run() {
			try {
				Reader r = new InputStreamReader(client.getInputStream(), "UTF-8");
				reader = new BufferedReader(r);
				String line = reader.readLine();
				client.close();
				if (line == null) {
					Res.logError("native longpoll remote error - no string sent: " + client.getRemoteSocketAddress());
					client.close();
					return;
				}
				if (Res.isDebug()) {
					Res.logInfo("native longpoll received string: " + line);
				}
				String[] parts = StringTools.splitToArray(line, ":", true, false);
				if (parts.length != 2) {
					Res.logError("native longpoll remote error - invalid string from: "
							+ client.getRemoteSocketAddress() + " String: " + line);
					client.close();
					return;
				}
				// verification
				WorkSource source = sources.get(parts[1]);
				if (source == null) {
					Res.logError("native longpoll remote error - Could not find registered native listening work source: "
							+ client.getRemoteSocketAddress() + " String: " + line);
					client.close();
					return;
				}
				if (!parts[0].equals(source.getNativeLongpollPassphrase())) {
					Res.logError("native longpoll remote error - incorrect passphrase: "
							+ client.getRemoteSocketAddress() + " String: " + line);
					client.close();
					return;
				}
				if (!source.isAllowedNativeLongpollAddress(addr)) {
					Res.logError("native longpoll remote error - remote host not allowed: "
							+ client.getRemoteSocketAddress() + " String: " + line);
					client.close();
					return;
				}
				// passed all checks
				blockTracker.reportNativeLonpoll(source);

			} catch (IOException e) {
				if (!timedout)
					Res.logError("native longpoll handler error", e);
			} finally {
				if (!timedout) {
					if (timeoutFuture != null) {
						timeoutFuture.cancel(false);
					}
					try {
						client.close();
					} catch (Exception e) {
					}
				}
			}
		}

	}

}
