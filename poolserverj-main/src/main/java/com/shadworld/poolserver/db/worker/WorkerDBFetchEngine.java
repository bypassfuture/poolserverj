package com.shadworld.poolserver.db.worker;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.sql.Sql;
import com.shadworld.utils.StringTools;

public class WorkerDBFetchEngine {
	
	public WorkerDBFetchEngine(String[] extraParams) {
		Res.logDebug("Building Worker-DB-fetch-engine: " + getClass().getSimpleName() + ".class with extraParams: " + (extraParams == null ? null : "[" + StringTools.concatStrings(extraParams, ",") + "]"));
		
	}
	
	/**
	 * retrieve multiple worker from database for cache preloading.
	 * @param usernameList a correctly SQL formatted list of usernames which will be
	 * inserted into the select statement in place of the ?.  e.g. for mysql the statement
	 * SELECT * FROM pool_worker WHERE username IN (?);
	 * should be provided with a list similar to : 'user1','user2'
	 * @return
	 * @throws SQLException
	 */
	public List<Worker> fetchWorkersById(List<Integer> ids) throws SQLException {
		if (ids == null || ids.size() == 0)
			return null;
		String query = Conf.getSqlSelectUserList();
		if (query == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(ids.size() * 2);
		for (Integer id: ids) {
			if (sb.length() > 0)
				sb.append(",");
			sb.append(id);
		}
		query = query.replace("?", sb.toString());
		Sql sql = Conf.getWorkerSql();
		
		sql.prepareConnection();
		
		ResultSet rs = sql.queryOrFail(query).getResultSet();
		List<Worker> workers = new ArrayList();
		Worker lastWorker = null;
		while (true) {
			lastWorker = getWorkerFromResultSet(rs);
			if (lastWorker == null)
				break;
			workers.add(lastWorker);
		} 
		return workers;
	}

	public Worker fetchWorker(String username) throws SQLException {
		String query = Conf.getSqlSelectUser();
		Sql sql = Conf.getWorkerSql();
		
		sql.prepareConnection();
		PreparedStatement stmt = sql.conn.prepareStatement(Conf.getSqlSelectUser());
		stmt.setString(1, username);
		ResultSet rs = stmt.executeQuery();
		return getWorkerFromResultSet(rs);
	}
	
	public Worker getWorkerFromResultSet(ResultSet rs) throws SQLException {
		if (rs.next()) {
			Worker worker = new Worker();
			worker.setUsername(rs.getString(Conf.getUsernameColumn()));
			worker.setPassword(rs.getString(Conf.getUserPasswordColumn()));
			worker.setId(rs.getInt(Conf.getUserIdColumn()));
			String allowedCol = Conf.getUserAllowedHostsColumn();
			if (allowedCol != null) {
				String allowed = rs.getString(allowedCol);
				if (allowed != null) {
					worker.setHostCheckingEnabled(true);
					worker.getAllowedHosts().addAll(StringTools.split(allowed, ","));
				}
			}
			return worker;
		}
		return null;
	}
	
}
