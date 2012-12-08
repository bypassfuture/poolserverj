package com.shadworld.poolserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.StatisticsServlet;
import org.eclipse.jetty.servlets.CloseableDoSFilter;
import org.eclipse.jetty.servlets.QoSFilter;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.logging.WorkRequestLogger;
import com.shadworld.poolserver.logging.ShareLogger;
import com.shadworld.poolserver.logging.ShareSubmitter;
import com.shadworld.poolserver.servlet.AbstractJsonRpcServlet;
import com.shadworld.poolserver.servlet.MgmtInterfaceServlet;
import com.shadworld.poolserver.servlet.PoolServerJLongpollServlet;
import com.shadworld.poolserver.servlet.PoolServerJServlet;
import com.shadworld.poolserver.servlet.PsjDosFilter;
import com.shadworld.poolserver.servlet.PsjQosFilter;
import com.shadworld.poolserver.source.DaemonSource;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;
import com.shadworld.util.Time;

public class PoolServer {

	private static PoolServer instance;

	final WorkRequestLogger workRequestLogger;
	final ShareSubmitter shareSubmitter;
	BlockChainTracker blockTracker;
	LongpollHandler longpollHandler;
	NativeLongpollListener nativeLongpollListener;

	final WorkProxy workProxy;

	final WorkerProxy workerProxy;

	Server jettyServer;
	Server mgmtServer;

	final Cleaner cleaner;

	File propsFile;

	private boolean shutdown = false;
	final private Object shutdownLock = new Object();

	private File workMapFile;

	private static final int GRACEFUL_SHUTDOWN_TIMEOUT = 5000;

	public static PoolServer get() {
		return instance;
	}

	// private ArrayList<Object> longPollThreadLocks = new ArrayList(); //moved
	// to BlockChainTracker

	// private static final String DEFAULT_PROPERTIES_FILE =
	// "PoolServerJ.properties";

	public PoolServer(String propertiesFile) throws FileNotFoundException, IllegalAccessException {
		this(false);
		File file = new File(propertiesFile);

		if (!file.exists()) {
			throw new FileNotFoundException("Specified properties file does not exist: " + file.getAbsolutePath());
		}
		if (!file.canRead()) {
			throw new IllegalAccessException("Specified properties file not readable: " + file.getAbsolutePath());
		}
		propsFile = file;
		Conf.init(this, propsFile);
	}

	public PoolServer() {
		this(true);
	}

	public Class<? extends Conf> ConfClass() {
		return Conf.class;
	}

	/**
	 * dummy constructor, this will not result in a usable server. It's only
	 * used to allow a basic Conf to be built so we can extract params for
	 * shutdown.
	 * 
	 * @param rubbish
	 */
	public PoolServer(String rubbish, String moreRubbish, String evenMoreRubbish) {
		workRequestLogger = null;
		shareSubmitter = null;
		blockTracker = null;
		workProxy = null;
		workerProxy = null;
		cleaner = null;
	}

	private PoolServer(boolean init) {
		workRequestLogger = new WorkRequestLogger();
		blockTracker = new BlockChainTracker(this);
		shareSubmitter = new ShareSubmitter(blockTracker);
		workProxy = buildWorkProxy();
		workerProxy = new WorkerProxy(this);
		cleaner = new Cleaner(this);
		Stats.setPoolServer(this);
		instance = this;
		if (init) {
			Conf.init(this, propsFile);
		}
	}

	protected HttpServlet buildMainServlet(boolean noLongpoll, String rollNTimeString) {
		return new PoolServerJServlet(this, noLongpoll ? null : Conf.get().getHttpLPJsonRpcUrl(), rollNTimeString);
	}

	// protected Handler buildMainHandler(boolean noLongpoll, int port) {
	// return new PoolServerJHandler(this, noLongpoll ? null :
	// Conf.get().getHttpLPJsonRpcUrl(), port);
	// }

	protected HttpServlet buildLongPollServlet(String rollNTimeString) {
		return new PoolServerJLongpollServlet(this, null, rollNTimeString);
	}

	// protected Handler buildLongpollHandler(int port) {
	// return new PoolServerJLongpollHandler(this, null, port);
	// }

	protected HttpServlet buildMgmtInterfaceServlet() {
		return new MgmtInterfaceServlet(this);
	}

	// protected Handler buildMgmtInterfaceHandler(int port) {
	// return new MgmtInterfaceHandler(this, port);
	// }

	protected WorkProxy buildWorkProxy() {
		return new WorkProxy(this);
	}

	public DaemonSource buildLocalDaemonSource(String name, String url, String username, String password, int weighting) {
		return new DaemonSource(getWorkProxy(), name, url, username, password, weighting);
	}

	/**
	 * override this method to perform any custom configurations before the
	 * server engine is started
	 */
	protected void onBeforeStartServer() {
	}

	/**
	 * this method is called before initiating shutdown. Override for custom
	 * behaviour but ensure this methd returns quickly as it is called before
	 * database flushing. For non-time critical action use
	 * onFinishedDatabaseFlushInShutdown()
	 */
	protected void onBeforeShutdownServer() {
	}

	/**
	 * called on shutdown as soon as database flushing is complete. Use this for
	 * more time consuming actions to avoid risk of process being killed before
	 * data is flushed.
	 */
	protected void onFinishedDatabaseFlushInShutdown() {
	}

	protected void onFinishedShutdownServer() {
	}

	public void start() {
		onBeforeStartServer();
		Res.initSharedJsonRpcClients(workProxy.getAllSources().size());
		// give shared getworkClient all credentials
		JsonRpcClient client = Res.getSharedClient();
		for (WorkSource source : workProxy.getAllSources()) {
			client.setHttpAuthCredentials(source.getUrl(), source.getUsername(), source.getPassword());
		}
		// don't do this for long poll clients, the url won't be discovered
		// until the getworkClient starts.
		// //same for longpoll getworkClient
		// getworkClient = Res.getLongpollSharedClient();
		// for (WorkSource source: workProxy.getAllSources()) {
		// getworkClient.setHttpAuthCredentials(source.getl, source.getUsername(),
		// source.getPassword());
		// }

		// workProxy.setLocalDaemonSource(new DaemonSource(workProxy,
		// "localBTCDaemon",
		// "http://localhost:8332/", "pushpool1", "slick6", 25));
		longpollHandler = new LongpollHandler(2, Conf.get().getHttpLPTimeout(), workProxy, blockTracker);

		blockTracker.setWorkProxy(workProxy);
		blockTracker.setWorkerProxy(workerProxy);
		blockTracker.start();
		cleaner.start();
		shareSubmitter.start();
		Runtime.getRuntime().addShutdownHook(new Thread("shutdown") {
			public void run() {
				shutdown(null);
			}
		});

		try {
			startHttpListenersWithServlets();
		} catch (Exception e) {
			Res.logException(e);
			System.exit(1);
		}
		if (workProxy.start() == 0) {
			Res.logError("No work sources configured, server cannot start.");
			System.exit(1);
		}
		Res.logInfo("PoolServerJ is open for business");
		
 		//start native LP handler;
		List<WorkSource> nativeSources = new ArrayList();
		for (WorkSource source: workProxy.getAllSources()) {
			if (source.isNativeLongpoll())
				nativeSources.add(source);
			Res.logDebug(source + " Native longpoll: " + source.isNativeLongpoll() + " verification: " + source.isNativeLongpollVerificationEnabled());
			Res.logDebug(source + " Native longpoll: " + source.isNativeLongpoll() + " verification: "
					+ source.isNativeLongpollVerificationEnabled());
		}
		if (nativeSources.size() > 0) {
			nativeLongpollListener = new NativeLongpollListener(blockTracker, Conf.get().getNativeLongpollPort(), Conf.get().getNativeLongpollTimeout());
			for (WorkSource source: nativeSources) {
				nativeLongpollListener.registerWorkSource(source);
			}
			Res.logInfo("Starting native longpoll listener for : " + nativeSources.size() + " sources");
			nativeLongpollListener.start();
		} else {
			Res.logDebug("No native longpoll listeners registered, all sources will fall back to polling mode");
		}

	}

	private static final String MAIN_CONNECTOR_NAME = "json-rpc-conn";
	private static final String LONGPOLL_CONNECTOR_NAME = "lp-conn";
	private static final String MGMT_CONNECTOR_NAME = "mgmt-ifc-conn";

	// private void startHttpListenersWithHandlers() throws Exception {
	//
	// jettyServer = new Server();
	//
	// HashSet<SelectChannelConnector> connectors = new HashSet();
	// HashSet<String> connectorNames = new HashSet();
	// List<ContextHandler> contextList = new ArrayList();
	//
	// SelectChannelConnector mainConnector = new SelectChannelConnector();
	// mainConnector.setPort(Conf.get().getHttpJsonRpcPort());
	// mainConnector.setMaxIdleTime(3000);
	// mainConnector.setName(MAIN_CONNECTOR_NAME);
	//
	// QueuedThreadPool mcThreadPool = new QueuedThreadPool();
	// mcThreadPool.setName("main-con-qtp");
	// mainConnector.setThreadPool(mcThreadPool);
	//
	// connectors.add(mainConnector);
	// connectorNames.add(MAIN_CONNECTOR_NAME);
	//
	// boolean noLongpoll = true;
	// SelectChannelConnector lpConnector = null;
	// if (Conf.get().getHttpLPJsonRpcUrl() != null &&
	// Conf.get().isEnableLongPoll()) {
	// if (Conf.get().getHttpLPJsonRpcPort() == Conf.get().getHttpJsonRpcPort())
	// {
	// Res.logError("Longpoll MUST be on a different port to main listener due to different timeout settings.");
	// System.exit(1);
	// }
	// lpConnector = new SelectChannelConnector();
	// lpConnector.setPort(Conf.get().getHttpLPJsonRpcPort());
	// lpConnector.setMaxIdleTime(Conf.get().getHttpLPTimeout());
	//
	// QueuedThreadPool lpThreadPool = new
	// QueuedThreadPool(Conf.get().getHttpLPMaxConnections());
	// lpThreadPool.setName("main-lpcon-qtp");
	//
	// lpConnector.setThreadPool(lpThreadPool);
	// lpConnector.setName(LONGPOLL_CONNECTOR_NAME);
	// connectors.add(lpConnector);
	// connectorNames.add(LONGPOLL_CONNECTOR_NAME);
	// noLongpoll = false;
	// }
	// ContextHandlerCollection contexts = new ContextHandlerCollection();
	//
	// if (!noLongpoll) {
	// // longpoll servlet has no longpoll url so it doesn't pass headers.
	// try {
	// URL url = new URL(Conf.get().getHttpLPJsonRpcUrl());
	// ContextHandler context = new ContextHandler();
	// // context.setContextPath(url.getPath());
	// context.setContextPath("/");
	// // context.setResourceBase(".");
	// context.setClassLoader(Thread.currentThread().getContextClassLoader());
	// context.setHandler(buildLongpollHandler(Conf.get().getHttpLPJsonRpcPort()));
	// context.setConnectorNames(new String[] { lpConnector.getName() });
	// contextList.add(context);
	// } catch (MalformedURLException e) {
	// // should never happen, already checked in Conf
	// }
	// }
	// // standard servlet, points to longpoll url
	// ContextHandler context = new ContextHandler();
	// String mainPath = Conf.get().getHttpJsonRpcPath();
	// context.setContextPath(mainPath == null || mainPath.isEmpty() ? "/" :
	// mainPath);
	// // context.setResourceBase(".");
	// context.setClassLoader(Thread.currentThread().getContextClassLoader());
	// context.setHandler(buildMainHandler(noLongpoll,
	// Conf.get().getHttpJsonRpcPort()));
	// context.setConnectorNames(new String[] { mainConnector.getName() });
	// if (contextList.size() > 0) {
	// List<ContextHandler> list = new ArrayList();
	// list.add(context);
	// list.addAll(contextList);
	// contextList = list;
	// }
	// // context.addServlet(new ServletHolder(buildMainServlet(noLongpoll)),
	// // "/");
	//
	// contexts.setHandlers(contextList.toArray(new
	// ContextHandler[contextList.size()]));
	//
	// jettyServer.setConnectors(connectors.toArray(new
	// SelectChannelConnector[connectors.size()]));
	// jettyServer.setHandler(contexts);
	// QueuedThreadPool mainThreadPool = new QueuedThreadPool();
	// mainThreadPool.setName("main-srv-qtp");
	// mainThreadPool.setMinThreads(1);
	//
	// PoolServerJQosFilter qosFilter = new PoolServerJQosFilter();
	//
	// jettyServer.setThreadPool(mainThreadPool);
	// jettyServer.start();
	//
	// // start management interface as separate server
	// if (Conf.get().isEnableManagentInterface()) {
	// if (Conf.get().getManagementInterfacePort() ==
	// Conf.get().getHttpJsonRpcPort()
	// || (!noLongpoll && Conf.get().getManagementInterfacePort() ==
	// Conf.get().getHttpLPJsonRpcPort())) {
	// Res.logError("Management interface must be bound to a different port to both main http interface and longpoll interface");
	// }
	// QueuedThreadPool mgmtiThreadPool = new QueuedThreadPool();
	// mgmtiThreadPool.setName("mgmt-ifc-con-qtp");
	// mgmtiThreadPool.setMinThreads(1);
	//
	// SelectChannelConnector mConnector = new SelectChannelConnector();
	// mConnector.setPort(Conf.get().getManagementInterfacePort());
	// mConnector.setMaxIdleTime(3000);
	// mConnector.setThreadPool(mgmtiThreadPool);
	// mConnector.setName(MGMT_CONNECTOR_NAME);
	// if (Conf.get().isBindManagementInterfaceOnlyToLocahost()) {
	// mConnector.setHost("127.0.0.1");
	// }
	// // connectors.add(mConnector);
	// // connectorNames.add(MGMT_CONNECTOR_NAME);
	// // ServletContextHandler mContext = new
	// // ServletContextHandler(ServletContextHandler.NO_SESSIONS
	// // | ServletContextHandler.NO_SECURITY);
	//
	// ContextHandler mContext = new ContextHandler();
	// mContext.setContextPath("/");
	// // context.setResourceBase(".");
	// mContext.setClassLoader(Thread.currentThread().getContextClassLoader());
	// mContext.setHandler(buildMgmtInterfaceHandler(Conf.get().getManagementInterfacePort()));
	// mContext.setConnectorNames(new String[] { mConnector.getName() });
	//
	// mgmtServer = new Server();
	// mgmtServer.setConnectors(new SelectChannelConnector[] { mConnector });
	// mgmtServer.setHandler(mContext);
	// QueuedThreadPool mgmtThreadPool = new QueuedThreadPool();
	// mgmtThreadPool.setName("mgmt-ifc-srv-qtp");
	// mgmtThreadPool.setMinThreads(1);
	//
	// mgmtServer.setThreadPool(mgmtThreadPool);
	// mgmtServer.start();
	// }
	// }

	/**
	 * @throws Exception
	 */
	private void startHttpListenersWithServlets() throws Exception {

		jettyServer = new Server();
		jettyServer.setSendDateHeader(false);
		jettyServer.setSendServerVersion(false);
		jettyServer.setGracefulShutdown(GRACEFUL_SHUTDOWN_TIMEOUT);
		jettyServer.setStopAtShutdown(true);
		// security
		// end security

		HashSet<SelectChannelConnector> connectors = new HashSet();
		HashSet<String> connectorNames = new HashSet();

		boolean sharedPort = Conf.get().getHttpLPJsonRpcPort() == Conf.get().getHttpJsonRpcPort();

		SelectChannelConnector mainConnector = new SelectChannelConnector();
		mainConnector.setPort(Conf.get().getHttpJsonRpcPort());

		mainConnector.setMaxIdleTime(sharedPort ? Conf.get().getHttpLPTimeout() : 3000);
		mainConnector.setName(MAIN_CONNECTOR_NAME);

		QueuedThreadPool mcThreadPool = new QueuedThreadPool();
		mcThreadPool.setName("main-con-qtp");
		mainConnector.setThreadPool(mcThreadPool);
		if (sharedPort) {
			mcThreadPool.setMaxThreads(Conf.get().getHttpLPMaxConnections());
		}

		connectors.add(mainConnector);
		connectorNames.add(MAIN_CONNECTOR_NAME);

		boolean noLongpoll = true;
		if (Conf.get().getHttpLPJsonRpcUrl() != null && Conf.get().isEnableLongPoll()) {
			if (Conf.get().getHttpLPJsonRpcPort() != Conf.get().getHttpJsonRpcPort()) {
				Res.logError("Longpoll is on a different port to main listener.  Some mining clients do not support this.");
				System.exit(1);
			}
			if (!sharedPort) {
				SelectChannelConnector lpConnector = new SelectChannelConnector();
				lpConnector.setPort(Conf.get().getHttpLPJsonRpcPort());
				lpConnector.setMaxIdleTime(Conf.get().getHttpLPTimeout());

				QueuedThreadPool lpThreadPool = new QueuedThreadPool(Conf.get().getHttpLPMaxConnections());
				lpThreadPool.setName("main-lpcon-qtp");

				lpConnector.setThreadPool(lpThreadPool);
				lpConnector.setName(LONGPOLL_CONNECTOR_NAME);
				connectors.add(lpConnector);
				connectorNames.add(LONGPOLL_CONNECTOR_NAME);
			}
			noLongpoll = false;
		}

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS
				| ServletContextHandler.NO_SECURITY);
		context.setContextPath("/");

		PsjQosFilter qosFilter = null;
		PsjDosFilter dosFilter = null;
		if (Conf.get().isEnableQoS()) {
			qosFilter = new PsjQosFilter(workerProxy);
			context.addFilter(new FilterHolder(qosFilter), "/*", EnumSet.allOf(DispatcherType.class));
			// setting are update after server is started.
		}
		if (Conf.get().isEnableDoSFilter()) {
			dosFilter = new PsjDosFilter(workerProxy);
			context.addFilter(new FilterHolder(dosFilter), "/*", EnumSet.allOf(DispatcherType.class));
			// setting are update after server is started.
		}

		// context.setAllowNullPathInfo(allowNullPathInfo)

		AbstractJsonRpcServlet longpollServlet = null;
		if (!noLongpoll) {
			longpollServlet = (AbstractJsonRpcServlet) buildLongPollServlet(Conf.get().getRollNTimeString());
			// longpoll servlet has no longpoll url so it doesn't pass headers.
			try {
				URL url = new URL(Conf.get().getHttpLPJsonRpcUrl());
				context.addServlet(new ServletHolder(longpollServlet),
						url.getPath());
			} catch (MalformedURLException e) {
				// should never happen, already checked in Conf
			}
		}
		// standard servlet, points to longpoll url
		AbstractJsonRpcServlet mainServlet = (AbstractJsonRpcServlet) buildMainServlet(noLongpoll, Conf.get().getRollNTimeString());
		mainServlet.setLongpollServlet(longpollServlet);
		context.addServlet(new ServletHolder(mainServlet), "/");

		HandlerList handlerList = null;
		if (Conf.get().isLogJettyStats()) {
			context.addServlet(new ServletHolder(new StatisticsServlet()), "/stats/");
			handlerList = new HandlerList();
			StatisticsHandler stats = new StatisticsHandler();

			handlerList.addHandler(stats);
			for (SelectChannelConnector conn : connectors)
				conn.setStatsOn(true);
			handlerList.addHandler(context);

		}

		context.setConnectorNames(connectorNames.toArray(new String[connectorNames.size()]));
		jettyServer.setConnectors(connectors.toArray(new SelectChannelConnector[connectors.size()]));
		if (Conf.get().isLogJettyStats())
			jettyServer.setHandler(handlerList);
		else
			jettyServer.setHandler(context);

		QueuedThreadPool mainThreadPool = new QueuedThreadPool();
		mainThreadPool.setName("main-srv-qtp");
		mainThreadPool.setMinThreads(1);
		jettyServer.setThreadPool(mainThreadPool);
		jettyServer.start();

		// start management interface as separate server
		if (Conf.get().isEnableManagentInterface()) {
			if (Conf.get().getManagementInterfacePort() == Conf.get().getHttpJsonRpcPort()
					|| (!noLongpoll && Conf.get().getManagementInterfacePort() == Conf.get().getHttpLPJsonRpcPort())) {
				Res.logError("Management interface must be bound to a different port to both main http interface and longpoll interface");
			}
			QueuedThreadPool mgmtiThreadPool = new QueuedThreadPool();
			mgmtiThreadPool.setName("mgmt-ifc-con-qtp");
			mgmtiThreadPool.setMinThreads(1);

			SelectChannelConnector mConnector = new SelectChannelConnector();
			mConnector.setPort(Conf.get().getManagementInterfacePort());
			mConnector.setMaxIdleTime(3000);
			mConnector.setThreadPool(mgmtiThreadPool);
			mConnector.setName(MGMT_CONNECTOR_NAME);
			if (Conf.get().isBindManagementInterfaceOnlyToLocahost()) {
				mConnector.setHost("127.0.0.1");
			}
			// connectors.add(mConnector);
			// connectorNames.add(MGMT_CONNECTOR_NAME);
			ServletContextHandler mContext = new ServletContextHandler(ServletContextHandler.NO_SESSIONS
					| ServletContextHandler.NO_SECURITY);
			mContext.setContextPath("/");
			mContext.addServlet(new ServletHolder(buildMgmtInterfaceServlet()), "/");

			mgmtServer = new Server();
			mgmtServer.setConnectors(new SelectChannelConnector[] { mConnector });
			mgmtServer.setHandler(mContext);
			QueuedThreadPool mgmtThreadPool = new QueuedThreadPool();
			mgmtThreadPool.setName("mgmt-ifc-srv-qtp");
			mgmtThreadPool.setMinThreads(1);

			mgmtServer.setThreadPool(mgmtThreadPool);
			mgmtServer.start();
		}

		// have to do this last because they aren't initialised until the server
		// starts and we get null pointers.
		if (qosFilter != null) {
			qosFilter.setMaxRequests(Conf.get().getQoSMaxRequestsToServiceConcurrently());
		}
		if (dosFilter != null) {
			dosFilter.setMaxRequestsPerSec(Conf.get().getDoSFilterMaxRequestsPerSecondBeforeThrottle());
		}
	}

	/**
	 * shutdown pool
	 * 
	 * @param writer
	 *            PrintWriter to write status messages to during shutdown. Can
	 *            be null.
	 */
	public void shutdown(final PrintWriter writer) {
		try {
			if (shutdown) // on manual shutdown the shutdown hook might
							// invoke shutdown() a second time.
				return;
			Res.logInfo(writer, "Shutting down poolserver... ");
			onBeforeShutdownServer();

			shareSubmitter.shutdown(writer); // do first to ensure results are
			// flushed to disk or database quickly;
			onFinishedDatabaseFlushInShutdown();
			Res.logInfo(writer, "Waiting for threads to die...");

			workRequestLogger.shutdown();
			workerProxy.shutdown();
			workProxy.shutdown();
			if (nativeLongpollListener != null)
				nativeLongpollListener.shutdown();
			blockTracker.shutdown();
			cleaner.shutdown();

			synchronized (Res.getGlobalLock()) {
				Res.getGlobalLock().notifyAll();
			}
			Conf.getWorkerSql().close();

			// wait for them all to die
			shareSubmitter.join();
			workRequestLogger.join();
			workerProxy.join();
			workProxy.join();
			try {
				cleaner.join();
			} catch (InterruptedException e2) {
			}
			try {
				blockTracker.join();
			} catch (InterruptedException e) {
			}

			Res.stopSharedJsonRpcClients();

			Res.logInfo(writer, "Shutting down Web Server...");
			if (mgmtServer != null) {
				if (writer != null) {// request probably came via mgmt interface
										// so
										// well send complete message before
										// shutdown.
					// Res.logInfo("" + mgmtServer.isRunning() +
					// mgmtServer.isStopping() + mgmtServer.isFailed());
					try {
						Res.logInfo(writer, "Shutdown server complete, shutting down management interface...");
					} catch (Exception e) {
						e.printStackTrace();
						if (writer != null)
							e.printStackTrace(writer);
					}

					// Release the shutdown lock early. Because we are probably
					// in a management interface handler shutdown the server will
					// cause this handler to be killed and we will never release
					// the lock so the main handler won't exit if it's blocked by
					// join()
					shutdown = true;
					synchronized (shutdownLock) {
						shutdownLock.notifyAll();
					}
				}
			}

			Thread shutdownLongpollThread = new Thread("shutdown-longpoll") {
				public void run() {
					try {
						//give us a minute to stop the server connectors.
						Thread.sleep(100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					long start = System.currentTimeMillis();
					int count = blockTracker.closeLongpollConnectionsAndShutdownThreadPool();
					long time = System.currentTimeMillis() - start;
					String out = "Closed " + count + " longpoll connections in " + time + "ms";
					Res.logDebug(out);
					if (writer != null) {
						try {
							writer.println(out);
						} catch (Exception e) {
						}
					}
				}
			};
			shutdownLongpollThread.start();
			try {
				// from now we aren't accepting new connections and have
				// GRACEFUL_SHUTDOWN_TIMEOUT to get all the LP closed.
				jettyServer.stop();
			} catch (Exception e1) {
				// Res.logException(e1);
			}

			try {
				mgmtServer.stop();
			} catch (Exception e1) {
				// Res.logException(e1);
			}

			try {
				jettyServer.join();
			} catch (InterruptedException e) {
			}

			if (mgmtServer != null) {
				try {
					mgmtServer.join();
				} catch (InterruptedException e) {
				}
			}
			// final database flush in case shares came in after flush but
			// before listeners were stopped
			shareSubmitter.getShareLogger().flushImmediate();
			workRequestLogger.shutdown();
			shareSubmitter.getShareLogger().shutdown(true);

			Res.logInfo("Shutdown complete...");
			onFinishedShutdownServer();
			shutdown = true;
			// release shutdownLock in case anyone is blocked by join();
			synchronized (shutdownLock) {
				shutdownLock.notifyAll();
			}
		} catch (Exception e) {
			Res.logInfo("SHUTDOWN FAILED");
			e.printStackTrace();
			if (writer != null)
				e.printStackTrace(writer);

		}
	}

	/**
	 * @deprecated
	 */
	private void buildConfig(String propsFile) {
		// // File propertiesFile = null;
		// // if (propsFile == null)
		// // propsFile = DEFAULT_PROPERTIES_FILE;
		// // propertiesFile = new File(propsFile);
		// // if (!propertiesFile.exists()) {
		// // propsFile = DEFAULT_PROPERTIES_FILE;
		// // propertiesFile = new File(propsFile);
		// // if (!propertiesFile.exists()) {
		// // Res.logError("Cannot find properties file at: " +
		// propertiesFile.getAbsolutePath(), null);
		// // System.exit(1);
		// // }
		// // }
		// try {
		// //props.load(new FileInputStream(propertiesFile));
		// Conf.get().update();
		// } catch (Exception e) {
		// Res.logError("Cannot find properties file at: " +
		// propertiesFile.getAbsolutePath(), e);
		// System.exit(1);
		// }

	}

	public void notifyLongPollSleepingThreads() {

	}

	/**
	 * @return the workProxy
	 */
	public WorkProxy getWorkProxy() {
		return workProxy;
	}

	/**
	 * @return the workerProxy
	 */
	public WorkerProxy getWorkerProxy() {
		return workerProxy;
	}

	/**
	 * @return the cleaner
	 */
	public Cleaner getCleaner() {
		return cleaner;
	}

	/**
	 * @return the blockTracker
	 */
	public BlockChainTracker getBlockTracker() {
		return blockTracker;
	}

	/**
	 * @return the workRequestLogger
	 */
	public WorkRequestLogger getWorkRequestLogger() {
		return workRequestLogger;
	}

	/**
	 * @return the longpollHandler
	 */
	public LongpollHandler getLongpollHandler() {
		return longpollHandler;
	}

	/**
	 * @return the shareSubmitter
	 */
	public ShareSubmitter getShareSubmitter() {
		return shareSubmitter;
	}

	public ShareLogger getShareLogger() {
		return shareSubmitter.getShareLogger();
	}

	public void join() {
		while (!shutdown) {
			synchronized (shutdownLock) {
				try {
					shutdownLock.wait();
				} catch (InterruptedException e) {
				}
			}
		}
	}

	public File getWorkMapFile() {
		return workMapFile;
	}

}
