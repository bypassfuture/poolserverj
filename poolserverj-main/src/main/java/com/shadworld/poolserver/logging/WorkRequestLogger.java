package com.shadworld.poolserver.logging;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.sql.Sql;

public class WorkRequestLogger {

private static String NEW_LINE = System.getProperty("line.separator", "\n");
	
final WorkRequestLoggingThread workRequestLoggingThread;
	
	boolean shutdown = false;
	
	int maxRequestsToQueueBeforeCommit = 5;
	long maxRequestAgeBeforeCommit = 5000;
	private boolean usePushPoolCompatibleFormat = false;
	boolean flushFilesImmedate = false;
	
	File outputFile;
	
	final ArrayDeque<RequestEntry> q = new ArrayDeque();
	
	public WorkRequestLogger() {
		super();
		workRequestLoggingThread = new WorkRequestLoggingThread(this, "work-request-logging-handler");
		//don't start this handler yet, first wait until we get a valid file to log to.
	}
	
	public void submitLogEntries(Collection<JsonRpcRequest> requests) {
		if (!workRequestLoggingThread.isAlive())
			return; //not enabled so don't waste time;
		ArrayList<RequestEntry> list = new ArrayList();
		for (JsonRpcRequest request: requests) {
			RequestEntry entry = new RequestEntry(request, System.currentTimeMillis());
			list.add(entry);
		}
		synchronized (q) {
			q.addAll(list);
		}
		if (flushFilesImmedate)
			flushToFile(list);
		synchronized (q) {
			q.notifyAll();
		}
	}
	
	public void submitLogEntry(JsonRpcRequest request) {
		if (!workRequestLoggingThread.isAlive())
			return; //not enabled so don't waste time;
		RequestEntry entry = new RequestEntry(request, System.currentTimeMillis());
		synchronized (q) {
			q.offerLast(entry);
		}
		if (flushFilesImmedate)
			flushToFile(Collections.singletonList(entry));
		synchronized (q) {
			q.notifyAll();
		}
	}
	
	public void shutdown() {
		shutdown = true;
		synchronized (this) {
			notifyAll();
		}
		synchronized (q) {
			if (!flushFilesImmedate) //already done on submission
				flushToFile(q);
			q.notifyAll();
		}
	}
	
	/**
	 * return value for bool dependant on whether pushpool compatible format is requested.
	 * @param bool
	 * @param ppNullValue value to return for null if pushpool mode
	 * @return
	 */
	private String getCompatibleValue(String string, String ppNullValue) {
		if (string == null)
			return usePushPoolCompatibleFormat ? ppNullValue : "null";
		return string;
	}
	
	/**
	 * return value for bool dependant on whether pushpool compatible format is requested.
	 * @param bool
	 * @param ppNullValue value to return for null if pushpool mode
	 * @return
	 */
	private String getCompatibleValue(Boolean bool, String ppNullValue) {
		if (bool == null)
			return usePushPoolCompatibleFormat ? "-" : "null";
		return usePushPoolCompatibleFormat ? bool ? "Y" : "N" : bool.toString();
	}
	
	synchronized void flushToFile(Collection<RequestEntry> results) {
		boolean logToFile = true;
		if (outputFile == null || !outputFile.exists())
			logToFile = false;
		if (outputFile != null && !outputFile.canWrite()) {
			Res.logError("Cannot write to output file: " + outputFile.getAbsolutePath());
			logToFile = false;
		}
		if (!logToFile && !Res.isLogRequestsToStdout())
			return;
		
		StringBuilder sb = new StringBuilder();
		FileWriter writer = null;
		//System.out.println("writing file");
		try {
			if (logToFile)
				writer = new FileWriter(outputFile, true);
			String separator = usePushPoolCompatibleFormat ? " " : ", ";
			
			for (RequestEntry entry: results) {
				JsonRpcRequest request = entry.getRequest();
				//sb.append(request.getId()).append(separator);
				if (usePushPoolCompatibleFormat)
					sb.append(LogUtil.getCompatibleTimeValue(entry.getSubmitTime())).append(separator);
				else
					sb.append(new Date(entry.getSubmitTime())).append(separator);
				sb.append(request.getRequesterIp()).append(separator);
				sb.append(LogUtil.getCompatibleValue(request.getUsername(), "-", "null", usePushPoolCompatibleFormat)).append(separator);
				sb.append("\"").append(request.getRequestUrl()).append("\"").append(NEW_LINE);
				
				String out = sb.toString();
				if (logToFile)
					writer.write(out);
				if (Res.isLogRequestsToStdout())
					System.out.print(out);
				sb.setLength(0);
			}
			if (logToFile)
				writer.close();
			
		} catch (IOException ex) {
			Res.logError("Enable to write shares to file: " + outputFile.getAbsolutePath(), ex);
		} finally {
			try {
				if (writer != null)
					writer.close();
			} catch (IOException ex) {
				Res.logError("Problem writing shares to file: " + outputFile.getAbsolutePath(), ex);
			}
		}
	}
	
	
	
	/**
	 * @return the maxRequestsToQueueBeforeCommit
	 */
	public int getMaxRequestsToQueueBeforeCommit() {
		return maxRequestsToQueueBeforeCommit;
	}

	/**
	 * @param maxRequestsToQueueBeforeCommit the maxRequestsToQueueBeforeCommit to set
	 */
	public void setMaxRequestsToQueueBeforeCommit(int maxRequestsToQueueBeforeCommit) {
		this.maxRequestsToQueueBeforeCommit = maxRequestsToQueueBeforeCommit;
	}

	/**
	 * @return the maxRequestAgeBeforeCommit
	 */
	public long getMaxRequestAgeBeforeCommit() {
		return maxRequestAgeBeforeCommit;
	}

	/**
	 * @param maxRequestAgeBeforeCommit the maxRequestAgeBeforeCommit to set
	 */
	public void setMaxRequestAgeBeforeCommit(long maxRequestAgeBeforeCommit) {
		this.maxRequestAgeBeforeCommit = maxRequestAgeBeforeCommit;
	}

	/**
	 * @return the usePushPoolCompatibleFormat
	 */
	public boolean isUsePushPoolCompatibleFormat() {
		return usePushPoolCompatibleFormat;
	}

	/**
	 * @param usePushPoolCompatibleFormat the usePushPoolCompatibleFormat to set
	 */
	public void setUsePushPoolCompatibleFormat(boolean usePushPoolCompatibleFormat) {
		this.usePushPoolCompatibleFormat = usePushPoolCompatibleFormat;
	}

	/**
	 * @return the flushFilesImmedate
	 */
	public boolean isFlushFilesImmedate() {
		return flushFilesImmedate;
	}

	/**
	 * @param flushFilesImmedate the flushFilesImmedate to set
	 */
	public void setFlushFilesImmedate(boolean flushFilesImmedate) {
		this.flushFilesImmedate = flushFilesImmedate;
	}

	/**
	 * @return the outputFile
	 */
	public File getOutputFile() {
		return outputFile;
	}

	/**
	 * Sets the output and starts the logging handler if valid writeable file
	 * @param outputFile the outputFile to set
	 */
	public void setOutputFile(File outputFile) {
		if (outputFile != null) {
			if (!outputFile.exists()) {
				if (outputFile.getParentFile() != null)
					outputFile.getParentFile().mkdirs();
					try {
						if (outputFile.createNewFile() && outputFile.canWrite() && !workRequestLoggingThread.isAlive()) {
							workRequestLoggingThread.start();
							this.outputFile = outputFile;
						}
					} catch (IOException e) {
						Res.logError("could not create request log file: " + outputFile.getAbsolutePath(), e);
					}
			} else if (outputFile.canWrite() && !workRequestLoggingThread.isAlive()) {
				workRequestLoggingThread.start();
				this.outputFile = outputFile;
			}
		}
	}



	public void join() {
		try {
			workRequestLoggingThread.join();
		} catch (InterruptedException e) {
		}
	}
}

