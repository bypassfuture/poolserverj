package com.shadworld.poolserver.db.shares;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;

import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.logging.LogUtil;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareLogger;
//import com.shadworld.protobuf.netty.ProtoClient;
//import com.shadworld.protobuf.wire.PsjProto.ShareUpdate;
//import com.shadworld.protobuf.wire.PsjProto.ShareUpdate.ShareResult;
//import com.shadworld.protobuf.wire.PsjProto.ShareUpdates;
//import com.shadworld.protobuf.wire.helper.AckListenerRegistry;
import com.shadworld.sql.MySql;
import com.shadworld.sql.Sql;
import com.shadworld.utils.L;
import com.shadworld.utils.Seq;
import com.shadworld.utils.StringTools;

/**
 * if subclassing you MUST ensure you have a constructor with the same signature as the existing one i.e.
 * <code>
 * 	public MySharesDBFlushEngine(ShareLogger logger, String notUsed, String[] extraParams) {
 *		super(logger, notUsed, extraParams);
 * }
 * </code>
 * @author git
 *
 */
public class DefaultPreparedStatementSharesDBFlushEngine {

//	INSERT INTO `poolserverj_native`.`shares_counter` (username, block_num, valid, stale, unknown, duplicate, efficiency) 
//	VALUES ('test-worker-client1', 141311, 1, 0, 0, 0, 11.5)
//	ON DUPLICATE KEY UPDATE valid = valid + VALUES(valid), stale = stale + VALUES(stale)
//	, unknown = unknown + VALUES(unknown), duplicate = duplicate + VALUES(duplicate)
//	, efficiency = VALUES(efficiency), hashrate_mh = (valid + stale + unknown + duplicate) / (NOW() - first_share_time) * 4295.967 ;
	
	private ShareLogger logger;
	
	protected boolean initialised = false;
	protected boolean usePushPoolCompatibleFormat;
	protected int numQueryParams;
	protected String insertQuery;
	
	protected String counterQuery = "INSERT INTO `poolserverj_native`.`shares_counter` (username, block_num, valid, stale, unknown, duplicate, efficiency)" 
					+ "VALUES (?, ?, ?, ?, ?, ?, ?)"
					+ "ON DUPLICATE KEY UPDATE valid = valid + VALUES(valid), stale = stale + VALUES(stale)"
					+ ", unknown = unknown + VALUES(unknown), duplicate = duplicate + VALUES(duplicate)"
					+ ", efficiency = VALUES(efficiency), hashrate_mh = (valid + stale + unknown + duplicate) / (NOW() - first_share_time) * 4295.967 ";
	
	
	//ProtoClient getworkClient;
	
	public DefaultPreparedStatementSharesDBFlushEngine(ShareLogger logger, String notUsed, String[] extraParams) {
		Res.logDebug("Building Shares-DB-flush-engine: " + getClass().getSimpleName() + ".class with extraParams: " + (extraParams == null ? null : "[" + StringTools.concatStrings(extraParams, ",") + "]"));
		this.logger = logger;
		usePushPoolCompatibleFormat = logger.isUsePushPoolCompatibleFormat();
		numQueryParams = logger.getNumQueryParams();
		insertQuery = logger.getInsertQuery();
		
		initProtoClient();
	}
	
	private void initProtoClient() {
		//getworkClient = new ProtoClient("localhost", 9001, "ps_test", "ps_test");
		//getworkClient.connect("localhost", 9001, "ps_test", "ps_test");
	}
	
	public void flushToDatabase(final Collection<ShareEntry> results) {
		if (results.isEmpty())
			return;
		
		if (!initialised) {
			initialised = true;
			usePushPoolCompatibleFormat = logger.isUsePushPoolCompatibleFormat();
			numQueryParams = logger.getNumQueryParams();
		}
		
		Sql sql = Conf.getSharesSql();
		
		int size = results.size();
		long start = System.currentTimeMillis();

		Res.logDebug("Doing database flush for Shares: " + results.size());
		if (sql == null || insertQuery == null)
			return;
		PreparedStatement stmt = null;
		try {
			sql.prepareConnection();
			stmt = sql.conn.prepareStatement(insertQuery);
			for (ShareEntry entry : results) {
				// stmt.setTime(8, new Time(98l))
				stmt.setString(1, entry.request.getRequesterIp());
				stmt.setString(2, entry.worker.getUsername());
				if (logger.isUsePushPoolCompatibleFormat()) {
					stmt.setString(3,
							LogUtil.getCompatibleValue(entry.ourResult, null, null, usePushPoolCompatibleFormat));
					stmt.setString(4,
							LogUtil.getCompatibleValue(entry.upstreamResult, null, null, usePushPoolCompatibleFormat));
				} else {
					stmt.setBoolean(3, entry.ourResult);
					stmt.setBoolean(4, entry.upstreamResult);
				}
				stmt.setString(5, LogUtil.getCompatibleValue(entry.reason, null, null, usePushPoolCompatibleFormat));
				if (numQueryParams >= 6)
					stmt.setString(6, entry.solution);
				if (numQueryParams >= 7) {
					if (usePushPoolCompatibleFormat)
						// stmt.setLong(9, entry.createTime);
						stmt.setTimestamp(7, new Timestamp(entry.createTime));
					else
						stmt.setTimestamp(7, new Timestamp(entry.createTime));
				}
				if (numQueryParams >= 8)
					stmt.setString(8, entry.source == null ? null : entry.source.getName());
				if (numQueryParams >= 9)
					stmt.setInt(9, entry.blocknum);
				if (numQueryParams >= 10) {
					stmt.setString(10, entry.solution.substring(8, 72));
				}
				stmt.addBatch();
			}
			int[] resultValues = stmt.executeBatch();

			// TODO add verification of query results and logging any individual
			// failures.

		} catch (SQLException e) {
			Res.logError("Failed to commit to database.", e);
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
		
		if (Res.isDebug()) {
			float time = System.currentTimeMillis() - start;
			int rate = (int) (size / (time / 1000));
			Res.logInfo("Flushed " + size + " shares to DB in " + time + "ms (" + rate + "/sec)");
		}

	}
	
	private void sendProtos(final Collection<ShareEntry> results) {
//		int blocknum = -1;
//		
//		
//		//holder for any shares that do not match the first blocknum in the list
//		//this is passed recursivly to sendProtos method until diffblock contains nothing
//		//proving that on the last pass results contianed shares from only a single block.
//		ArrayList<ShareEntry> diffBlock = new ArrayList();
//		
//		ShareUpdates.Builder builder = ShareUpdates.newBuilder();
//		builder.setSequenceId(Seq.newIntSeqId());
//		builder.setClientIdentifier("ps_test");
//		for (ShareEntry entry: results) {
//			if (!builder.hasBlocknum()) {
//				builder.setBlocknum(entry.blocknum);
//				blocknum = entry.blocknum;
//			} else if (blocknum != entry.blocknum && blocknum > 0) {
//				diffBlock.add(entry);
//				continue;
//			}
//			ShareUpdate.Builder update = ShareUpdate.newBuilder();
//			update.setWorkerId(entry.worker.getId());
//			update.setReceiveTimestamp(entry.createTime);
//			if (entry.ourResult) {
//				update.setOurResult(entry.upstreamResult ? ShareResult.ACCEPTED : ShareResult.ACCEPTED_WINNING);
//			} else if (entry.reason == null) {
//				continue;
//			} else if (entry.reason.startsWith("un")) {
//				update.setOurResult(ShareResult.UNKNOWN);
//			} else if (entry.reason.startsWith("st")) {
//				update.setOurResult(ShareResult.STALE);
//			} else if (entry.reason.startsWith("du")) {
//				update.setOurResult(ShareResult.DUPLICATE);
//			} else if (entry.reason.startsWith("ex")) {
//				update.setOurResult(ShareResult.EXPIRED);
//			} else if (entry.reason.startsWith("ta")) {
//				update.setOurResult(ShareResult.TARGET_NOT_MET);
//			}
//			builder.addShareUpdates(update);
//		}
//		//getworkClient.H_SHARE_UPDATES.wrapPayload(builder);
//		getworkClient.getChannel().write(getworkClient.H_SHARE_UPDATES.wrapPayload(builder)).awaitUninterruptibly();
//		if (diffBlock.size() > 0) {
//			sendProtos(diffBlock);
//		}
	}
	
	public void updateCounters(final Collection<ShareEntry> results) {
		int size = results.size();
		long start = System.currentTimeMillis();

//		if (getworkClient != null) {
//			Res.logDebug("Doing protobuf counter update for Shares: " + results.size());
//			sendProtos(results);
//			if (Res.isDebug()) {
//				float time = System.currentTimeMillis() - start;
//				int rate = (int) (size / (time / 1000));
//				Res.logInfo("Updated " + size + " share counters to protoserver in " + time + "ms (" + rate + "/sec)");
//			}
//			return;
//		}
		Sql sql = Conf.getSharesSql();
		if (!(sql instanceof MySql)) {
			L.info("Update counters not implemented yet.");
			return;
		}
		
		if (results.isEmpty())
			return;
		
		if (!initialised) {
			initialised = true;
			usePushPoolCompatibleFormat = logger.isUsePushPoolCompatibleFormat();
			numQueryParams = logger.getNumQueryParams();
		}
		
		
		Res.logDebug("Doing counter update for Shares: " + results.size());
		if (sql == null || insertQuery == null)
			return;
		PreparedStatement stmt = null;
	
		try {
			sql.prepareConnection();
			stmt = sql.conn.prepareStatement(counterQuery);
			for (ShareEntry entry : results) {
				// stmt.setTime(8, new Time(98l))
				stmt.setString(1, entry.request.getUsername());
				stmt.setInt(2, entry.blocknum);
				
				if (entry.ourResult) {
					stmt.setInt(3, 1);
					stmt.setInt(4, 0);
					stmt.setInt(5, 0);
					stmt.setInt(6, 0);
				} else {
					stmt.setInt(3, 0);
					stmt.setInt(4, entry.reason.startsWith("st") ? 1 : 0);
					stmt.setInt(5, entry.reason.startsWith("un") ? 1 : 0);
					stmt.setInt(6, entry.reason.startsWith("du") ? 1 : 0);
				}
				stmt.setFloat(7, (float) entry.worker.efficiency());
				
				stmt.addBatch();
			}
			int[] resultValues = stmt.executeBatch();

			// TODO add verification of query results and logging any individual
			// failures.

		} catch (SQLException e) {
			Res.logError("Failed to update counters to database.", e);
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException e) {
					Res.logException(e);
				}
			}
		}
		
		if (Res.isDebug()) {
			float time = System.currentTimeMillis() - start;
			int rate = (int) (size / (time / 1000));
			Res.logInfo("Updated " + size + " share counters to DB in " + time + "ms (" + rate + "/sec)");
		}

	}

	public ShareLogger getLogger() {
		return logger;
	}

	public void setLogger(ShareLogger logger) {
		this.logger = logger;
	}

	public void shutdown() {
		//subclasses use this.
	}
	
}
