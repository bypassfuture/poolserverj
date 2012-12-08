package com.shadworld.poolserver.servlet;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConnection;

import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;
import com.shadworld.utils.StringTools;

public class MgmtInterfaceServlet extends HttpServlet {

	PoolServer server;

	public MgmtInterfaceServlet(PoolServer server) {
		super();
		this.server = server;
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

		// int port =
		// HttpConnection.getCurrentConnection().getConnector().getLocalPort();
		// if (port != allowedPort) {
		// return;
		// }
		// baseRequest.setHandled(true);

		String remote = req.getRemoteAddr();

		if (Res.isDebug()) {
			Res.logInfo("Received mgmt interface hit from: " + remote);
		}

		if (!Conf.get().getAllowedManagementAddresses().contains(remote)) {
			Res.logInfo(new Date() + " - Disallowed address attempted to access management interface.  Remote Addr: "
					+ remote + " Query params: " + req.getQueryString());
			return;
		}
		String method = req.getParameter("method");
		if (method == null)
			return;

		if ("flushWorker".equals(method)) {
			String username = req.getParameter("name");
			if (username == null) {
				resp.getWriter().println("no name specified");
			} else {
				server.getWorkerProxy().removeWorker(username);
				resp.getWriter().println("ok");
			}
		} else if ("flushWorkers".equals(method)) {
			String username = req.getParameter("names");
			if (username == null) {
				resp.getWriter().println("no names specified");
			} else {
				List<String> workers = StringTools.split(username, ",", true, false);
				for (String worker : workers)
					server.getWorkerProxy().removeWorker(username);
				resp.getWriter().println("ok");
			}
		} else if ("shutdown".equals(method)) {
			if (Conf.get().isAllowShutdownByManagementInterface()) {
				Res.logInfo("Shutdown initiated by management interface.  Remote Addr: " + remote);
				server.shutdown(resp.getWriter());
				resp.getWriter().println("shutdown initiated");
			} else {
				Res.logInfo("Shutdown attempted by management interface but denied by policy.  Remote Addr: " + remote);
				resp.getWriter().println("disallowed by policy");
			}
		} else if ("getproxystats".equals(method)) {
			resp.getWriter().println(Stats.get().getWorkProxyStats());
		} else if ("getsourcestats".equals(method)) {
			// long usedMem = (Runtime.getRuntime().maxMemory() -
			// Runtime.getRuntime().freeMemory()) / (1024 * 1024);
			double usedMem = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024)) / 1024d;
			Runtime.getRuntime().gc();
			double newUsedMem = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024)) / 1024d;
			// long newUsedMem = Runtime.getRuntime().maxMemory() -
			// Runtime.getRuntime().freeMemory() / (1024 * 1024);
			resp.getWriter().println(
					"Memory used: " + newUsedMem + " MB - Freed by GC: " + (usedMem - newUsedMem) + "MB");
			for (WorkSource source : server.getWorkProxy().getAllSources()) {
				resp.getWriter().println(source.getState().toString());
			}
			resp.getWriter().println(Stats.get().getSourceStatsString());
		} else if ("fireblockchange".equals(method)) {
			server.getBlockTracker().fireBlockChange(server.getWorkProxy().getDaemonSource());
			resp.getWriter().println("done\n");
		} else if ("setCacheSize".equals(method) || "setAllCacheSize".equals(method)) {

			String value = req.getParameter("value");
			int val = -1;
			try {
				val = Integer.parseInt(value);
			} catch (Exception e) {
			}
			if (val == -1) {
				resp.getWriter().println("Unparseable int value: " + value + " must specify value=<newCacheSize>");
				return;
			}
			if ("setCacheSize".equals(method)) {
				String source = req.getParameter("source");
				WorkSource workSource = server.getWorkProxy().getSourceByName(source);
				if (workSource == null) {
					resp.getWriter().println("Source not found: " + source + " must specify source=<sourcename>");
					return;
				}
				workSource.setMaxCacheSize(val);
			} else {
				for (WorkSource workSource: server.getWorkProxy().getAllSources()) {
					workSource.setMaxCacheSize(val);
				}
			}
		} else if ("setMaxConcurrentDl".equals(method) || "setAllMaxConcurrentDl".equals(method)) {
			String value = req.getParameter("value");
			int val = -1;
			try {
				val = Integer.parseInt(value);
			} catch (Exception e) {
			}
			if (val == -1) {
				resp.getWriter().println("Unparseable int value: " + value + " must specify value=<newMax>");
				return;
			}
			if ("setMaxConcurrentDl".equals(method)) {
				String source = req.getParameter("source");
				WorkSource workSource = server.getWorkProxy().getSourceByName(source);
				if (workSource == null) {
					resp.getWriter().println("Source not found: " + source + " must specify source=<sourcename>");
					return;
				}
				workSource.setMaxConcurrentDownloadRequests(val);
			} else {
				for (WorkSource workSource: server.getWorkProxy().getAllSources()) {
					workSource.setMaxConcurrentDownloadRequests(val);
				}
			}
		} else if ("setMaxWorkAgeToFlush".equals(method) || "setAllMaxWorkAgeToFlush".equals(method)) {
			String value = req.getParameter("value");
			int val = -1;
			try {
				val = Integer.parseInt(value);
			} catch (Exception e) {
			}
			if (val == -1) {
				resp.getWriter().println("Unparseable int value: " + value + " must specify value=<newMax>");
				return;
			}
			if ("setMaxWorkAgeToFlush".equals(method)) {
				String source = req.getParameter("source");
				WorkSource workSource = server.getWorkProxy().getSourceByName(source);
				if (workSource == null) {
					resp.getWriter().println("Source not found: " + source + " must specify source=<sourcename>");
					return;
				}
				workSource.setMaxWorkAgeToFlush(val * 1000);
			} else {
				for (WorkSource workSource: server.getWorkProxy().getAllSources()) {
					workSource.setMaxWorkAgeToFlush(val * 1000);
				}
			}
		} else if ("listWorkerCache".equals(method)) {
			String list = server.getWorkerProxy().getSortedWorkerListString();
			resp.getWriter().println(list);
			return;
		} else if ("threadDump".equals(method)) {
			Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
			for (Entry<Thread, StackTraceElement[]> e : traces.entrySet()) {
				resp.getWriter().println("StackTrace for Thread: [" + e.getKey().getName() + "]");
				for (int i=0; i < e.getValue().length; i++)
	                resp.getWriter().println("\tat " + e.getValue()[i]);
				resp.getWriter().println();
			}
			
		} else if ("log".equals(method)) {
			resp.setContentType("text/html");
			Object waitLock = new Object();
			synchronized (waitLock) {
				Res.setLiveLogWriter(resp.getWriter(), waitLock);
				try {
					waitLock.wait();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

	}

}
