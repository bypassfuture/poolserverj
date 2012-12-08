package com.shadworld.poolserver.servicelauncher;

import java.io.File;
import java.io.IOException;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.Buffer;

import com.mysql.jdbc.Util;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Conf.Properties;
import com.shadworld.poolserver.test.RunContinuousClientTests;
import com.shadworld.utils.L;

/**
 * Simple service class
 */
public class PoolServerService {

	/**
	 * Single static instance of the service class
	 */
	private static PoolServerService serviceInstance = new PoolServerService();

	public static void main(String args[]) {
		windowsService(args);

	}

	/**
	 * Static method called by prunsrv to start/stop the service. Pass the
	 * argument "start" to start the service, and pass "stop" to stop the
	 * service.
	 */
	public static void windowsService(String args[]) {
		try {
			System.out.print("Args - ");
			if (args != null) {
				System.out.print("[" + args.length + "]: ");
				for (String arg : args)
					System.out.print(arg + " ");
			} else
				System.out.print("null");
			System.out.println("");

			String cmd = "start";
			serviceInstance.propertiesFile = null;
			if (args.length > 0) {
				cmd = args[0];
			}
			if (args.length > 1) {
				serviceInstance.propertiesFile = args[1];
			}

			if ("start".equals(cmd)) {
				serviceInstance.start();
			} else if ("stop".equals(cmd)) {
				serviceInstance.stop();
			} else if ("stress-test-getworkClient".equalsIgnoreCase(cmd)) {
				String[] newArgs = new String[args.length - 1];
				System.arraycopy(args, 1, newArgs, 0, newArgs.length);
				RunContinuousClientTests.main(newArgs);
			} else {
				serviceInstance.stop();
				printUsage();
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
		// System.out.println("finished start()");
	}

	public static void printUsage() {
		System.out.println("USAGE: poolserverj start <properties-file>");
		System.out.println("       poolserverj stop  <properties-file>");
		System.out.println("       poolserverj stress-test-getworkClient <getworkClient-test-properties-file>");
	}

	/**
	 * Flag to know if this service instance has been stopped.
	 */
	private boolean stopped = false;

	private PoolServer poolServer;

	private String propertiesFile;

	/**
	 * Start this service instance
	 */
	public void start() {

		System.out.println("PoolServerJ Service Starting " + new java.util.Date());

		try {
			poolServer = propertiesFile == null ? new PoolServer() : new PoolServer(propertiesFile);
			poolServer.start();
			stopped = false;
			System.out.println("PoolServerJ Service Started " + new java.util.Date());
		} catch (Exception e) {
			System.out.println("Failed to start");
			e.printStackTrace();
			stopped = true;
		}
		while (!stopped) {

			synchronized (this) {
				try {
					this.wait();
				} catch (InterruptedException ie) {
					System.out.println("Interupted exception caught");

				}
			}//
		}

		System.out.println("PoolServerJ Service Finished " + new java.util.Date());

	}

	/**
	 * Stop this service instance
	 */
	public void stop() {
		if (poolServer != null) {
			if (poolServer != null) {
				poolServer.shutdown(null);
				poolServer.join();
			}
			stopped = true;
			synchronized (this) {
				this.notifyAll();
			}
		} else {
			//server is not running in this jvm instance so we'll try sending
			// a shutdown signal via management interface.
			try {
				//org.eclipse.jetty.util.log.JavaUtilLog
				//java.util.logging.LogManager.getLogManager().
				//poolServer = propertiesFile == null ? new PoolServer() : new PoolServer(propertiesFile);
				
				File propsFile = new File(propertiesFile);
				Conf.init(new PoolServer(null, null, null), propsFile, false);
				Properties props = Conf.get().loadProperties(propsFile);
				Conf.get().configureHtpListenerParams(props);
				
				HttpClient client = new HttpClient();
				client.start();
				final boolean[] failed = new boolean[]{false};
				ContentExchange ex = new ContentExchange() {

					/* (non-Javadoc)
					 * @see org.eclipse.jetty.client.ContentExchange#onResponseContent(org.eclipse.jetty.io.Buffer)
					 */
					@Override
					protected synchronized void onResponseContent(Buffer content) throws IOException {
						super.onResponseContent(content);
						content.writeTo(System.out);
					}

					/* (non-Javadoc)
					 * @see org.eclipse.jetty.client.HttpExchange#onConnectionFailed(java.lang.Throwable)
					 */
					@Override
					protected void onConnectionFailed(Throwable x) {
						//super.onConnectionFailed(x);
						failed[0] = true;
						System.out.println("Connection to management interface failed due to: " + x.getMessage());
						System.out.println("Management interface must be enabled to stop server from outside process.  Try using an OS specific end signal (e.g. ctrl-C) to trigger shutdown.");
						System.out.println("Cannot stop server.");
					}

					/* (non-Javadoc)
					 * @see org.eclipse.jetty.client.HttpExchange#onException(java.lang.Throwable)
					 */
					@Override
					protected void onException(Throwable x) {
						//super.onException(x);
						if ("local close".equalsIgnoreCase(x.getMessage())) {
							System.out.println("shutdown complete");
							return;
						}
						failed[0] = true;
						System.out.println("Connection to management interface failed due to: " + x.getMessage());
						System.out.println("Management interface must be enabled to stop server from outside process.  Try using an OS specific end signal (e.g. ctrl-C) to trigger shutdown.");
						System.out.println("Cannot stop server.");
					}

					/* (non-Javadoc)
					 * @see org.eclipse.jetty.client.HttpExchange#onExpire()
					 */
					@Override
					protected void onExpire() {
						super.onExpire();
						failed[0] = true;
						System.out.println("Connection to management interface timed out.  Cannot stop server.");
						System.out.println("Management interface must be enabled to stop server from outside process.  Try using an OS specific end signal (e.g. ctrl-C) to trigger shutdown.");
						System.out.println("Cannot stop server.");
					}
				};
				ex.setMethod("GET");
				ex.setURL("http://localhost:" + Conf.get().getManagementInterfacePort() + "/?method=shutdown");
				client.send(ex);
				int result = ex.waitForDone();
				
//				if (failed[0]) {
//					failed[0] = false;
//					ex.reset();
//					getworkClient.send(ex);
//					result = ex.waitForDone();
//				}
				
			} catch (Exception e) {
				System.out.println("Failed to stop");
				e.printStackTrace();
				stopped = true;
			} 
		}

	}

}
